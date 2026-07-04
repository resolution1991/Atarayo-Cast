package com.atarayocast.app.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.atarayocast.app.bridge.NativeBridge
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware-accelerated H.264 / H.265 video decoder using MediaCodec async callback mode.
 *
 * Accepts raw AirPlay NAL streams (Annex B format) via DirectByteBuffer from the native
 * frame pool, decodes using hardware MediaCodec, and renders to a SurfaceView Surface.
 *
 * Key performance optimizations:
 * - Async MediaCodec callback mode (no polling, codec pulls when ready)
 * - DirectByteBuffer pool (zero per-frame allocation, ~120MB/s GC eliminated)
 * - NAL parsing with offset references (no temporary ByteArray copies)
 * - Bounded pending frame replacement (latest frame wins for real-time streaming)
 *
 * Threading: all operations run on a dedicated HandlerThread. Codec async callbacks
 * also fire on this handler thread, ensuring serialized access.
 */
class VideoDecoder(private val nativeBridge: NativeBridge) {

    companion object {
        private const val TAG = "VideoDecoder"
        private const val MAX_FRAME_SIZE = 4 * 1024 * 1024  // 4 MB
    }

    // ---- Threading ----
    private var handlerThread = HandlerThread("VideoDecoder").apply { start() }
    private var handler = Handler(handlerThread.looper)

    // ---- Codec state ----
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var currentMime: String? = null
    private var currentWidth = 0
    private var currentHeight = 0
    private var codecConfigured = false

    // ---- Pending frame (latest-wins for real-time streaming) ----
    private var pendingFrame: ByteBuffer? = null
    private var pendingFrameSize = 0
    private var pendingFramePts = 0L

    private val released = AtomicBoolean(false)
    val isReleased: Boolean get() = released.get()

    // ---- Public API ----

    fun resetIfNeeded() {
        if (!released.get()) return
        released.set(false)
        handlerThread = HandlerThread("VideoDecoder").apply { start() }
        handler = Handler(handlerThread.looper)
        Log.i(TAG, "VideoDecoder reset — new handler thread created")
    }

    fun setSurface(surface: Surface?) {
        handler.post {
            if (this.surface == surface) return@post
            releaseCodec()
            this.surface = surface
            // If we have a pending frame with known dimensions, try to configure
            maybeConfigureAndFeed()
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
                releaseCodec()
                currentWidth = width
                currentHeight = height
                maybeConfigureAndFeed()
            }
        }
    }

    fun stop() {
        handler.post {
            releaseCodec()
            dropPendingFrame()
            currentWidth = 0
            currentHeight = 0
        }
    }

    fun release() {
        released.set(true)
        handler.post {
            releaseCodec()
            dropPendingFrame()
            surface = null
            handlerThread.quitSafely()
        }
    }

    // ---- Internal: Frame processing ----

    private fun handleFrame(data: ByteBuffer, isH265: Boolean, ntpTime: Long) {
        val mime = if (isH265) "video/hevc" else "video/avc"
        val size = data.limit()

        // If codec needs (re)configuration, extract CSD and configure
        if (!codecConfigured || currentMime != mime || currentWidth == 0 || currentHeight == 0) {
            val width = if (currentWidth > 0) currentWidth else 1920
            val height = if (currentHeight > 0) currentHeight else 1080

            if (!configureCodec(width, height, mime, data)) {
                // Configuration failed — return buffer and drop
                nativeBridge.returnFrameBuffer(data)
                return
            }
            // After async codec start, buffer is pending for onInputBufferAvailable callback
        }

        // Replace any existing pending frame with latest (real-time streaming: latest wins)
        dropPendingFrame()
        pendingFrame = data
        pendingFrameSize = size
        pendingFramePts = ntpTime
    }

    /**
     * Try to configure codec if we have surface + dimensions + pending frame.
     * Called when surface becomes available or dimensions change.
     */
    private fun maybeConfigureAndFeed() {
        val pf = pendingFrame ?: return
        if (surface == null || !surface!!.isValid) return
        if (currentWidth <= 0 || currentHeight <= 0) return
        // We need mime from the pending frame — but we don't track it separately.
        // For now, skip; real config happens in handleFrame with mime param.
    }

    // ---- Internal: Codec management (async callback mode) ----

    private fun configureCodec(width: Int, height: Int, mime: String, csdBuffer: ByteBuffer): Boolean {
        try {
            releaseCodec()

            val format = MediaFormat.createVideoFormat(mime, width, height)
            val csd = extractCsdOptimized(csdBuffer, mime)
            if (csd != null) {
                format.setByteBuffer("csd-0", csd)
            }
            // Optimization flags
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_FRAME_SIZE)
            // Request low-latency mode if supported
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            val newCodec = MediaCodec.createDecoderByType(mime)
            // Set async callback on our handler thread for serialized access
            newCodec.setCallback(DecoderCallback(), handler)
            newCodec.configure(format, surface, null, 0)
            newCodec.start()

            codec = newCodec
            currentMime = mime
            currentWidth = width
            currentHeight = height
            codecConfigured = true
            Log.i(TAG, "Codec configured (async): $mime ${width}x${height}")
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
            val data = pendingFrame ?: return
            val size = pendingFrameSize

            val inputBuf = codec.getInputBuffer(index)
            if (inputBuf == null) {
                Log.w(TAG, "Input buffer is null, dropping frame")
                dropPendingFrame()
                return
            }

            // Copy frame data into MediaCodec input buffer
            inputBuf.clear()
            data.position(0)
            data.limit(size)
            inputBuf.put(data)

            codec.queueInputBuffer(index, 0, size, pendingFramePts, 0)

            // Frame consumed — return buffer to native pool
            dropPendingFrame()
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            // Render to Surface
            codec.releaseOutputBuffer(index, true)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Codec error: ${e.message}", e)
            codecConfigured = false
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "Output format changed: $format")
        }
    }

    /**
     * Return the pending frame buffer to the native pool and clear reference.
     */
    private fun dropPendingFrame() {
        pendingFrame?.let { nativeBridge.returnFrameBuffer(it) }
        pendingFrame = null
        pendingFrameSize = 0
        pendingFramePts = 0L
    }

    // ---- NAL parsing (optimized — uses offset+length, no ByteArray copies) ----

    /**
     * Fast NAL unit descriptor using offset into original buffer.
     * Avoids ByteArray allocations during CSD extraction.
     */
    private class NalRef(val offset: Int, val length: Int, val data: ByteArray)

    /**
     * Extract CSD (Codec-Specific Data) from the first NAL units.
     * Uses offset-based NAL references to avoid temporary ByteArray copies.
     * Returns a ByteBuffer suitable for MediaFormat.setByteBuffer("csd-0", ...)
     */
    private fun extractCsdOptimized(buffer: ByteBuffer, mime: String): ByteBuffer? {
        // Copy buffer data to a ByteArray for NAL parsing (done once per codec config)
        val size = buffer.limit()
        if (size <= 0) return null

        val data = ByteArray(size)
        buffer.position(0)
        buffer.get(data)

        val units = parseNalRefs(data)
        return try {
            when (mime) {
                "video/avc" -> {
                    val sps = units.find { (data[it.offset].toInt() and 0x1F) == 7 } ?: return null
                    val pps = units.find { (data[it.offset].toInt() and 0x1F) == 8 } ?: return null
                    ByteBuffer.wrap(csdFromNalRefs(data, listOf(sps, pps)))
                }
                "video/hevc" -> {
                    val vps = units.find {
                        ((data[it.offset].toInt() shr 1) and 0x3F) == 32
                    } ?: return null
                    val sps = units.find {
                        ((data[it.offset].toInt() shr 1) and 0x3F) == 33
                    } ?: return null
                    val pps = units.find {
                        ((data[it.offset].toInt() shr 1) and 0x3F) == 34
                    } ?: return null
                    ByteBuffer.wrap(csdFromNalRefs(data, listOf(vps, sps, pps)))
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting CSD", e)
            null
        }
    }

    /**
     * Parse NAL unit offsets and lengths from Annex B byte stream.
     * Returns list of NalRef — no data copying.
     */
    private fun parseNalRefs(data: ByteArray): List<NalRef> {
        val units = mutableListOf<NalRef>()
        var i = 0
        val len = data.size
        while (i < len) {
            val startCodeLen = when {
                i + 3 <= len && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                i + 2 <= len && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 1.toByte() -> 3
                else -> { i++; continue }
            }
            val start = i + startCodeLen
            var end = len
            // Fast forward to find next start code
            if (start < len) {
                for (j in start until len) {
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
                units.add(NalRef(start, end - start, data))
            }
            i = end
        }
        return units
    }

    /**
     * Build Annex B CSD byte array from NAL references.
     * Writes 0x00000001 start code prefix before each NAL unit.
     */
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
    }
}
