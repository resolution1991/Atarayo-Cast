package com.atarayocast.app.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import com.atarayocast.app.bridge.NativeBridge
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware-accelerated H.264 / H.265 video decoder using MediaCodec async callback mode.
 *
 * Key design decisions:
 * - **Input index queue**: When onInputBufferAvailable fires but no frame is pending,
 *   the index is saved. When a frame arrives, it's immediately fed using a saved index.
 *   This is CRITICAL in async mode — MediaCodec calls onInputBufferAvailable once per
 *   slot at startup; if we don't queueInputBuffer for a slot, it won't call back again.
 * - **Surface lifecycle**: When the Surface is destroyed (Activity navigates away),
 *   the codec is NOT released. Instead:
 *     - Frames continue being fed (decoded), but rendering is skipped (releaseOutputBuffer false).
 *     - When the Surface returns, setOutputSurface() (API 23+) reattaches it.
 *     - If the codec errors out while surface is gone, it will be released and the next
 *       CSD frame will trigger a fresh configuration.
 * - DirectByteBuffer pool for zero per-frame allocation
 * - NAL parsing with offset references (no temporary ByteArray copies)
 * - Ordered, bounded input queue. H.264/H.265 P/B frames depend on previous
 *   reference frames, so dropping or replacing an arbitrary pending frame can
 *   corrupt the reference chain and produce persistent macroblock artifacts.
 */
class VideoDecoder(private val nativeBridge: NativeBridge) {

    companion object {
        private const val TAG = "VideoDecoder"
        private const val MAX_FRAME_SIZE = 6 * 1024 * 1024  // 6 MB
        private const val VERBOSE = false
        private const val STARTUP_OUTPUT_DROP_COUNT = 2
        private const val MAX_PENDING_FRAMES = 8
    }

    // ---- Threading ----
    // THREAD_PRIORITY_DISPLAY gives the decoder thread higher scheduling priority
    // than default threads, reducing latency when the system is under load.
    private var handlerThread = HandlerThread("VideoDecoder", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private var handler = Handler(handlerThread.looper)

    // ---- Codec state ----
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var currentMime: String? = null
    private var currentWidth = 0
    private var currentHeight = 0
    private var codecConfigured = false
    private var waitingForKeyFrame = true
    private var startupOutputDropRemaining = 0

    // ---- Pending frames in decode order ----
    private data class PendingFrame(
        val data: ByteBuffer,
        val size: Int,
        val pts: Long,
        val isKeyFrame: Boolean
    )

    private val pendingFrames = ArrayDeque<PendingFrame>()
    private var pendingFrameBytes = 0

    // ---- Available input buffer indices (saved when no frame is pending) ----
    // CRITICAL: In async mode, MediaCodec calls onInputBufferAvailable for each
    // available input slot. If we don't queueInputBuffer, the slot is "consumed"
    // and the callback won't fire again. So we save the index and use it when
    // a frame becomes available.
    private val availableInputIndices = ArrayDeque<Int>()

    // ---- Stats ----
    private var feedCount = 0
    private var outputCount = 0
    private var dropCount = 0

    private val released = AtomicBoolean(false)
    val isReleased: Boolean get() = released.get()

    // ---- Public API ----

    fun resetIfNeeded() {
        if (!released.get()) return
        released.set(false)
        handlerThread = HandlerThread("VideoDecoder", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        handler = Handler(handlerThread.looper)
        Log.i(TAG, "VideoDecoder reset — new handler thread created")
    }

    /**
     * Set the output Surface for video rendering.
     *
     * Surface lifecycle (critical for Settings navigation):
     * - Surface → null: Surface destroyed (Activity paused/navigated away).
     *   Codec is NOT released — it stays alive but rendering is skipped.
     * - null → Surface: Surface recreated. If codec is alive, use
     *   setOutputSurface() (API 23+) to reattach. If codec was released
     *   (e.g. due to error), the next CSD frame will trigger fresh config.
     */
    fun setSurface(surface: Surface?) {
        handler.post {
            if (surface == null) {
                // Surface detached — keep codec alive.
                // Frames continue being decoded but rendering is skipped
                // (checked in onOutputBufferAvailable).
                val wasAlive = codec != null && codecConfigured
                this.surface = null
                Log.i(TAG, "Surface detached (codec alive=$wasAlive)")
                return@post
            }

            // Surface (re)attaching
            val hadCodec = (codec != null && codecConfigured)
            this.surface = surface

            if (hadCodec) {
                // Codec still alive — try to reattach to new surface
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 23) {
                        codec!!.setOutputSurface(surface)
                        Log.i(TAG, "Surface reattached via setOutputSurface")
                        return@post
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "setOutputSurface failed: ${e.message}")
                }
                // setOutputSurface not available or failed — release and
                // wait for next CSD frame to reconfigure
                releaseCodec()
                this.surface = surface
                Log.i(TAG, "Codec released for surface swap, will reconfigure on next CSD")
            } else {
                Log.i(TAG, "Surface set: valid=${surface.isValid} (codec not configured)")
            }
        }
    }

    /**
     * Decode a video frame from a DirectByteBuffer (native frame pool).
     * The buffer is owned by the native pool and must be returned via
     * [NativeBridge.returnFrameBuffer] after consumption.
     */
    fun decodeFrame(data: ByteBuffer, isH265: Boolean, ntpTime: Long) {
        val size = data.limit()
        if (size <= 0 || size > MAX_FRAME_SIZE) {
            Log.w(TAG, "Invalid frame size: $size, returning buffer")
            nativeBridge.returnFrameBuffer(data)
            return
        }
        handler.post {
            if (released.get()) {
                nativeBridge.returnFrameBuffer(data)
                return@post
            }
            handleFrame(data, isH265, ntpTime)
        }
    }

    fun setSize(width: Int, height: Int) {
        handler.post {
            if (width != currentWidth || height != currentHeight) {
                Log.i(TAG, "Video size changed: ${currentWidth}x${currentHeight} -> ${width}x${height}")
                // With adaptive playback (KEY_MAX_WIDTH/HEIGHT), the codec handles
                // resolution changes internally via SPS in the bitstream — no need
                // to release/recreate. Just update tracked dimensions.
                currentWidth = width
                currentHeight = height
            }
        }
    }

    fun stop() {
        handler.post {
            releaseCodec()
            dropPendingFrames()
            currentWidth = 0
            currentHeight = 0
            waitingForKeyFrame = true
            startupOutputDropRemaining = 0
        }
    }

    /**
     * Flush pending frames and reset codec for display recovery.
     *
     * Uses MediaCodec.flush() API — this flushes the codec's internal input/output
     * buffers WITHOUT releasing or reconfiguring the codec. The codec stays alive
     * with its current configuration. The next IDR frame will re-sync the decoder.
     *
     * This is safe to call mid-stream and does NOT cause the screen to go black
     * (the Surface stays attached, codec stays configured).
     */
    fun flush() {
        handler.postAtFrontOfQueue {
            dropPendingFrames()
            availableInputIndices.clear()
            waitingForKeyFrame = true
            startupOutputDropRemaining = STARTUP_OUTPUT_DROP_COUNT
            try {
                codec?.flush()
                codec?.start()
                Log.i(TAG, "Codec flushed/restarted — waiting for next IDR to re-sync")
            } catch (e: Exception) {
                Log.w(TAG, "MediaCodec.flush() failed, doing full reset", e)
                releaseCodec()
                currentWidth = 0
                currentHeight = 0
                waitingForKeyFrame = true
                startupOutputDropRemaining = 0
            }
        }
    }

    fun release() {
        released.set(true)
        handler.post {
            releaseCodec()
            dropPendingFrames()
            surface = null
            handlerThread.quitSafely()
        }
    }

    // ---- Internal: Frame processing ----

    private fun handleFrame(data: ByteBuffer, isH265: Boolean, ntpTime: Long) {
        val mime = if (isH265) "video/hevc" else "video/avc"
        val size = data.limit()

        if (!startsWithNalStartCode(data, size)) {
            if (VERBOSE) Log.w(TAG, "Dropping invalid video frame without leading NAL start code (size=$size)")
            nativeBridge.returnFrameBuffer(data)
            return
        }

        val isKeyFrame = isIdrFrame(data, size, isH265)

        // If codec needs (re)configuration, extract CSD and configure
        if (!codecConfigured || currentMime != mime || currentWidth == 0 || currentHeight == 0) {
            // Cannot configure without a surface — drop frame and wait
            if (surface == null) {
                if (VERBOSE) Log.d(TAG, "Cannot configure codec: no surface, dropping frame ($size bytes)")
                nativeBridge.returnFrameBuffer(data)
                return
            }

            val hasCsd = hasCodecConfig(data, size, isH265)
            if (!isKeyFrame || !hasCsd) {
                if (VERBOSE) {
                    Log.i(
                        TAG,
                        "Waiting for clean sync frame before codec config " +
                            "(isIDR=$isKeyFrame hasCSD=$hasCsd size=$size)"
                    )
                }
                nativeBridge.returnFrameBuffer(data)
                return
            }

            val width = if (currentWidth > 0) currentWidth else 1920
            val height = if (currentHeight > 0) currentHeight else 1080

            if (!configureCodec(width, height, mime, data, isH265)) {
                // Configuration failed — drop frame
                nativeBridge.returnFrameBuffer(data)
                return
            }

            // CRITICAL: After configuration, the first frame (which contains
            // SPS+PPS+IDR) must ALSO be fed to the decoder as input data.
            // Previously we returned the buffer here, which meant the IDR slice
            // data was lost — the decoder had CSD (SPS/PPS) but no first IDR
            // picture. All subsequent P-frames referenced the missing IDR,
            // causing macroblock artifacts until the next IDR frame arrived.
            Log.i(TAG, "Codec configured, queuing first IDR frame (size=$size)")
            waitingForKeyFrame = false
            startupOutputDropRemaining = STARTUP_OUTPUT_DROP_COUNT
            enqueueFrame(data, size, ntpTime, isKeyFrame = true)
            tryFeedPendingFrames()
            return
        }

        if (waitingForKeyFrame) {
            if (!isKeyFrame) {
                if (VERBOSE) Log.d(TAG, "Dropping non-IDR frame while waiting for decoder re-sync")
                nativeBridge.returnFrameBuffer(data)
                return
            }
            waitingForKeyFrame = false
            startupOutputDropRemaining = STARTUP_OUTPUT_DROP_COUNT
            if (VERBOSE) Log.i(TAG, "Decoder re-sync IDR received; suppressing first outputs")
        }

        if (!enqueueFrame(data, size, ntpTime, isKeyFrame)) return
        tryFeedPendingFrames()

        if (VERBOSE && (feedCount % 30 == 0 || availableInputIndices.isEmpty())) {
            Log.i(TAG, "Frame queued: feedCount=$feedCount size=$size isIDR=$isKeyFrame" +
                " availSlots=${availableInputIndices.size} pending=${pendingFrames.size}")
        }
    }

    // ---- Internal: Queue and feed pending frames to codec ----

    /**
     * Queue a frame in decode order. If we fall behind far enough that the queue
     * would grow unbounded, the only artifact-safe recovery is to discard queued
     * reference state and wait for the next IDR frame.
     */
    private fun enqueueFrame(data: ByteBuffer, size: Int, pts: Long, isKeyFrame: Boolean): Boolean {
        if (pendingFrames.size >= MAX_PENDING_FRAMES) {
            dropCount += pendingFrames.size + 1
            Log.w(
                TAG,
                "Decoder input queue overflow: dropping ${pendingFrames.size + 1} frames, " +
                    "waiting for next IDR (fed=$feedCount rendered=$outputCount)"
            )
            dropPendingFrames()
            waitingForKeyFrame = true
            startupOutputDropRemaining = 0

            if (!isKeyFrame) {
                nativeBridge.returnFrameBuffer(data)
                return false
            }

            waitingForKeyFrame = false
            startupOutputDropRemaining = STARTUP_OUTPUT_DROP_COUNT
            Log.i(TAG, "Queue overflow recovered on incoming IDR")
        }

        pendingFrames.addLast(PendingFrame(data, size, pts, isKeyFrame))
        pendingFrameBytes += size
        return true
    }

    /**
     * Attempt to feed queued frames to the codec using saved input buffer indices.
     * Called both from handleFrame (when a frame arrives) and from onInputBufferAvailable
     * (when a new slot becomes available).
     */
    private fun tryFeedPendingFrames() {
        val c = codec ?: return

        while (availableInputIndices.isNotEmpty() && pendingFrames.isNotEmpty()) {
            val index = availableInputIndices.removeFirst()
            feedFrameToCodec(c, index)
        }
    }

    /**
     * Feed the next queued frame into the codec at the given input buffer index.
     * Returns the ByteBuffer to the native pool after copying.
     */
    private fun feedFrameToCodec(c: MediaCodec, index: Int) {
        if (pendingFrames.isEmpty()) {
            // No pending frame — put the index back
            availableInputIndices.addFirst(index)
            return
        }
        val frame = pendingFrames.removeFirst()

        pendingFrameBytes -= frame.size

        try {
            val inputBuf = c.getInputBuffer(index)
            if (inputBuf == null) {
                Log.w(TAG, "getInputBuffer returned null for index=$index")
                nativeBridge.returnFrameBuffer(frame.data)
                return
            }

            // Copy frame data into MediaCodec input buffer
            inputBuf.clear()
            frame.data.position(0)
            frame.data.limit(frame.size)
            inputBuf.put(frame.data)

            // Set BUFFER_FLAG_KEY_FRAME for IDR frames so the codec knows
            // this is a sync point — helps with error recovery and prevents
            // macroblock artifacts from propagating after dropped frames.
            val flags = if (frame.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            c.queueInputBuffer(index, 0, frame.size, frame.pts, flags)
            feedCount++

            if (VERBOSE && (feedCount % 30 == 0 || frame.isKeyFrame)) {
                Log.i(TAG, "Fed frame #$feedCount to codec (size=${frame.size}, isIDR=${frame.isKeyFrame}" +
                    ", availSlots=${availableInputIndices.size}, queued=${pendingFrames.size})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error feeding frame to codec", e)
            waitingForKeyFrame = true
            startupOutputDropRemaining = 0
        }

        // Frame consumed — return buffer to native pool
        nativeBridge.returnFrameBuffer(frame.data)
    }

    // ---- Internal: Codec management (async callback mode) ----

    private fun configureCodec(width: Int, height: Int, mime: String, csdBuffer: ByteBuffer, isH265: Boolean): Boolean {
        try {
            releaseCodec()

            val format = MediaFormat.createVideoFormat(mime, width, height)
            val csd = extractCsdOptimized(csdBuffer, mime)
            if (csd != null) {
                format.setByteBuffer("csd-0", csd)
                if (VERBOSE) Log.i(TAG, "CSD extracted: ${csd.remaining()} bytes for $mime")
            } else {
                Log.w(TAG, "No CSD found in first frame, codec may not produce output")
            }

            // ---- Buffer size ----
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_FRAME_SIZE)

            // ---- Phase A optimization: Decoder scheduling hints ----
            // KEY_PRIORITY=0 (realtime): tells the decoder this is a live stream,
            //   prioritize low-latency scheduling over throughput.
            // KEY_OPERATING_RATE=120: hints target frame rate × 2 headroom so the
            //   decoder can allocate internal pipeline resources aggressively.
            // KEY_FRAME_RATE=60: expected input frame rate for scheduling.
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 60)

            // ---- Phase A optimization: Color metadata ----
            // AirPlay screen mirroring uses H.264 High Profile with BT.709 color
            // standard (SDR 8-bit, limited range 16-235). Without these keys the
            // decoder may use incorrect YUV-to-RGB conversion matrices, causing
            // washed-out or shifted colors.
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)

            // ---- Phase A optimization: Adaptive playback ----
            // KEY_MAX_WIDTH/HEIGHT enables adaptive playback: the codec handles
            // resolution changes (via SPS in the bitstream) without needing to be
            // released and recreated. Most modern devices support this feature.
            format.setInteger(MediaFormat.KEY_MAX_WIDTH, 3840)
            format.setInteger(MediaFormat.KEY_MAX_HEIGHT, 2160)

            // ---- Low latency (API 30+) ----
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            // Check adaptive playback support via MediaCodecList (API 21+)
            val adaptiveSupported = try {
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                codecList.codecInfos.any { info ->
                    !info.isEncoder && try {
                        info.getCapabilitiesForType(mime)
                            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)
                    } catch (e: Exception) { false }
                }
            } catch (e: Exception) { false }
            if (VERBOSE) Log.i(TAG, "Adaptive playback supported: $adaptiveSupported")

            val newCodec = MediaCodec.createDecoderByType(mime)
            newCodec.setCallback(DecoderCallback(), handler)
            newCodec.configure(format, surface, null, 0)
            newCodec.start()

            codec = newCodec
            currentMime = mime
            currentWidth = width
            currentHeight = height
            codecConfigured = true
            feedCount = 0
            outputCount = 0
            dropCount = 0
            availableInputIndices.clear()
            Log.i(TAG, "Codec configured (async): $mime ${width}x${height} surface=${surface != null}" +
                " color=BT.709/limited priority=realtime adaptive=$adaptiveSupported")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure codec", e)
            codecConfigured = false
            return false
        }
    }

    /**
     * Async MediaCodec callback — fires on our HandlerThread for serialized access.
     */
    private inner class DecoderCallback : MediaCodec.Callback() {

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // CRITICAL FIX: Save the index even if no frame is pending.
            // When a frame arrives later, it will use this saved index.
            availableInputIndices.addLast(index)
            tryFeedPendingFrames()
            if (VERBOSE) {
                Log.d(
                    TAG,
                    "onIBA: idx=$index availSlots=${availableInputIndices.size} queued=${pendingFrames.size}"
                )
            }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            // If surface was detached (Activity navigated away), release
            // without rendering — the codec stays alive for later reattachment.
            if (surface == null) {
                codec.releaseOutputBuffer(index, false)
                return
            }

            try {
                if (startupOutputDropRemaining > 0) {
                    codec.releaseOutputBuffer(index, false)
                    startupOutputDropRemaining--
                    if (VERBOSE) {
                        Log.i(TAG, "Suppressed startup output frame, remaining=$startupOutputDropRemaining")
                    }
                    return
                }

                // Phase A optimization: use timestamp API (API 21+) instead of
                // boolean render flag. Passing 0L renders immediately (same as
                // render=true), but uses the timestamp code path which is
                // required for future PTS-based frame pacing.
                codec.releaseOutputBuffer(index, 0L)
                outputCount++
            } catch (e: Exception) {
                // Surface might have been destroyed asynchronously —
                // release without rendering to avoid crash.
                Log.w(TAG, "Render failed (surface destroyed?), releasing without render")
                try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {}
            }

            if (VERBOSE && outputCount % 30 == 0) {
                Log.i(TAG, "Rendered frame #$outputCount (pts=${info.presentationTimeUs})")
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Codec error: ${e.message}", e)
            codecConfigured = false
            dropPendingFrames()
            availableInputIndices.clear()
            waitingForKeyFrame = true
            startupOutputDropRemaining = 0
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "Output format changed: $format")
        }
    }

    /**
     * Return all queued frame buffers to the native pool and clear references.
     */
    private fun dropPendingFrames() {
        while (pendingFrames.isNotEmpty()) {
            nativeBridge.returnFrameBuffer(pendingFrames.removeFirst().data)
        }
        pendingFrameBytes = 0
    }

    // ---- IDR detection ----

    /**
     * Check if the frame contains an IDR NAL unit.
     *
     * H.264: NAL type 5 (IDR). The NAL type is (first_byte & 0x1F).
     * H.265: NAL types 16-21 (BLA_W_LP through IDR_W_RADL). The NAL type
     * is ((first_byte >> 1) & 0x3F).
     *
     * We scan the first few bytes of each NAL unit after the start code
     * (00 00 01 or 00 00 00 01). This is a lightweight scan — we only
     * check the first byte of each NAL unit body, not the full content.
     */
    private fun isIdrFrame(buffer: ByteBuffer, size: Int, isH265: Boolean): Boolean {
        if (size < 4) return false

        var i = 0
        while (i < size - 3) {
            // Look for start code: 00 00 01 or 00 00 00 01
            if (buffer.get(i) == 0.toByte() && buffer.get(i + 1) == 0.toByte()) {
                val nalStart: Int
                if (i + 3 < size && buffer.get(i + 2) == 1.toByte()) {
                    nalStart = i + 3
                } else if (i + 4 < size && buffer.get(i + 2) == 0.toByte() && buffer.get(i + 3) == 1.toByte()) {
                    nalStart = i + 4
                } else {
                    i += 2
                    continue
                }

                if (nalStart < size) {
                    val firstByte = buffer.get(nalStart).toInt() and 0xFF
                    if (isH265) {
                        val nalType = (firstByte shr 1) and 0x3F
                        // 16=BLA_W_LP, 17=BLA_W_RADL, 18=BLA_N_LP,
                        // 19=IDR_W_RADL, 20=IDR_N_LP, 21=CRA
                        if (nalType in 16..21) return true
                    } else {
                        val nalType = firstByte and 0x1F
                        if (nalType == 5) return true  // IDR slice
                    }
                }
                i = nalStart
            } else {
                i++
            }
        }
        return false
    }

    private fun startsWithNalStartCode(buffer: ByteBuffer, size: Int): Boolean {
        if (size < 4) return false
        if (buffer.get(0) != 0.toByte() || buffer.get(1) != 0.toByte()) return false
        if (buffer.get(2) == 1.toByte()) return true
        return size > 3 && buffer.get(2) == 0.toByte() && buffer.get(3) == 1.toByte()
    }

    /**
     * Codec configuration must come from the same access unit as a key frame at
     * stream start. Configuring from a P-frame or from an invalid frame lets the
     * decoder build references from incomplete state, which is the usual source
     * of startup macroblock artifacts.
     */
    private fun hasCodecConfig(buffer: ByteBuffer, size: Int, isH265: Boolean): Boolean {
        if (size < 4) return false

        var hasVps = !isH265
        var hasSps = false
        var hasPps = false
        var i = 0

        while (i < size - 3) {
            if (buffer.get(i) == 0.toByte() && buffer.get(i + 1) == 0.toByte()) {
                val nalStart: Int
                if (i + 2 < size && buffer.get(i + 2) == 1.toByte()) {
                    nalStart = i + 3
                } else if (i + 3 < size && buffer.get(i + 2) == 0.toByte() && buffer.get(i + 3) == 1.toByte()) {
                    nalStart = i + 4
                } else {
                    i += 2
                    continue
                }

                if (nalStart < size) {
                    val firstByte = buffer.get(nalStart).toInt() and 0xFF
                    if (isH265) {
                        when ((firstByte shr 1) and 0x3F) {
                            32 -> hasVps = true
                            33 -> hasSps = true
                            34 -> hasPps = true
                        }
                    } else {
                        when (firstByte and 0x1F) {
                            7 -> hasSps = true
                            8 -> hasPps = true
                        }
                    }
                    if (hasVps && hasSps && hasPps) return true
                }
                i = nalStart
            } else {
                i++
            }
        }
        return false
    }

    // ---- NAL parsing (optimized — uses offset+length, no ByteArray copies) ----

    private class NalRef(val offset: Int, val length: Int)

    /**
     * Extract CSD (Codec-Specific Data) from the first NAL units.
     */
    private fun extractCsdOptimized(buffer: ByteBuffer, mime: String): ByteBuffer? {
        val size = buffer.limit()
        if (size <= 0) return null

        val data = ByteArray(size)
        buffer.position(0)
        buffer.get(data)

        val units = parseNalRefs(data)
        if (VERBOSE) Log.i(TAG, "NAL parse: ${units.size} units found in $size bytes ($mime)")
        return try {
            when (mime) {
                "video/avc" -> {
                    val sps = units.find { (data[it.offset].toInt() and 0x1F) == 7 } ?: run {
                        Log.w(TAG, "No SPS NAL found")
                        return null
                    }
                    val pps = units.find { (data[it.offset].toInt() and 0x1F) == 8 } ?: run {
                        Log.w(TAG, "No PPS NAL found")
                        return null
                    }
                    ByteBuffer.wrap(csdFromNalRefs(data, listOf(sps, pps)))
                }
                "video/hevc" -> {
                    val vps = units.find {
                        ((data[it.offset].toInt() shr 1) and 0x3F) == 32
                    } ?: run {
                        Log.w(TAG, "No VPS NAL found")
                        return null
                    }
                    val sps = units.find {
                        ((data[it.offset].toInt() shr 1) and 0x3F) == 33
                    } ?: run {
                        Log.w(TAG, "No SPS NAL found")
                        return null
                    }
                    val pps = units.find {
                        ((data[it.offset].toInt() shr 1) and 0x3F) == 34
                    } ?: run {
                        Log.w(TAG, "No PPS NAL found")
                        return null
                    }
                    ByteBuffer.wrap(csdFromNalRefs(data, listOf(vps, sps, pps)))
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting CSD", e)
            null
        }
    }

    private fun parseNalRefs(data: ByteArray): List<NalRef> {
        val units = mutableListOf<NalRef>()
        var i = 0
        val len = data.size
        while (i < len - 2) {
            val startCodeLen = when {
                i + 3 < len && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                i + 2 < len && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 1.toByte() -> 3
                else -> { i++; continue }
            }
            val start = i + startCodeLen
            var end = len
            if (start < len) {
                for (j in start until len - 2) {
                    val b = data[j]
                    if (b == 0.toByte() && j + 1 < len && data[j + 1] == 0.toByte()) {
                        if (j + 2 < len && data[j + 2] == 1.toByte()) {
                            end = j; break
                        }
                        if (j + 3 < len && data[j + 2] == 0.toByte() && data[j + 3] == 1.toByte()) {
                            end = j; break
                        }
                    }
                }
            }
            if (start < end) {
                units.add(NalRef(start, end - start))
            }
            i = end
        }
        return units
    }

    private fun csdFromNalRefs(data: ByteArray, units: List<NalRef>): ByteArray {
        var totalLen = 0
        for (u in units) totalLen += 4 + u.length
        val csd = ByteArray(totalLen)
        var pos = 0
        for (u in units) {
            csd[pos++] = 0
            csd[pos++] = 0
            csd[pos++] = 0
            csd[pos++] = 1
            System.arraycopy(data, u.offset, csd, pos, u.length)
            pos += u.length
        }
        return csd
    }

    // ---- Codec release ----

    private fun releaseCodec() {
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing codec", e)
        }
        codec = null
        currentMime = null
        codecConfigured = false
        availableInputIndices.clear()
        waitingForKeyFrame = true
        startupOutputDropRemaining = 0
    }
}
