package com.atarayocast.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.atarayocast.app.bridge.NativeBridge
import java.nio.ByteBuffer

/**
 * Handles AirPlay audio playback.
 *
 * AirPlay sends audio as one of:
 * - ct=0: PCM 16-bit LE — written directly to AudioTrack
 * - ct=2: ALAC — decoded via NativeBridge's software ALAC decoder
 * - ct=4: AAC-LC — decoded via Android MediaCodec
 * - ct=8: AAC-ELD — decoded via Android MediaCodec
 *
 * macOS screen mirroring uses AAC-ELD (ct=8).
 * AirPlay Audio (music) uses ALAC (ct=2).
 *
 * Standard AirPlay audio: 44100 Hz, stereo, 16-bit.
 *
 * Thread safety: all methods are called from RAOP's native pthreads,
 * so internal state is protected by synchronized blocks.
 */
class AudioPlayer(
    private val nativeBridge: NativeBridge,
    private val context: Context
) {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 44100
        private const val CT_PCM = 0
        private const val CT_ALAC = 2
        private const val CT_AAC_LC = 4
        private const val CT_AAC_ELD = 8
        private const val MAX_DECODE_LOOP = 16

        // AudioSpecificConfig for AAC-ELD 44100 Hz, 2 channels
        // Decoded: audioObjectType=39 (ELD), samplingFreqIndex=4 (44100), channels=2
        private val AAC_ELD_CSD = byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50.toByte(), 0x00.toByte())

        // AudioSpecificConfig for AAC-LC 44100 Hz, 2 channels
        private val AAC_LC_CSD = byteArrayOf(0x12.toByte(), 0x10.toByte())
    }

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var audioTrack: AudioTrack? = null
    private var alacHandle: Long = 0L
    private var aacDecoder: MediaCodec? = null
    private var currentCt: Int = -1
    private var spf: Int = 0
    @Volatile private var running = false
    @Volatile private var focusDucked = false
    private var focusRequest: AudioFocusRequest? = null
    // Pre-allocated decode output buffer to reduce GC pressure
    private var decodeBuffer: ByteArray = ByteArray(0)

    // AirPlay volume gain (0.0-1.0), set from Mac's volume slider.
    // This controls ONLY the AudioTrack's own gain — it does NOT touch
    // the Android system media volume. Default is 1.0 (full volume).
    @Volatile private var airplayGain = 1.0f

    /**
     * Called from onAudioFormat callback to configure audio output.
     * Re-initializes AudioTrack and decoder if format changes.
     */
    @Synchronized
    fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        Log.i(TAG, "Audio format: ct=$ct, spf=$spf, screen=$usingScreen")

        if (ct == currentCt && this.spf == spf && audioTrack != null) {
            Log.d(TAG, "Format unchanged, skipping re-init")
            return
        }

        // Teardown previous config
        releaseAudioTrack()
        releaseAlacDecoder()
        releaseAacDecoder()

        currentCt = ct
        this.spf = spf

        // Initialize decoder based on codec type
        when (ct) {
            CT_ALAC -> {
                alacHandle = nativeBridge.initAlacDecoder(
                    frameLength = spf,
                    numChannels = 2,
                    bitDepth = 16,
                    pb = 2,
                    mb = 2,
                    kb = 1
                )
                if (alacHandle == 0L) {
                    Log.e(TAG, "Failed to initialize ALAC decoder (spf=$spf)")
                    return
                }
                Log.i(TAG, "ALAC decoder initialized: spf=$spf, handle=$alacHandle")
            }
            CT_AAC_ELD, CT_AAC_LC -> {
                if (!initAacDecoder(ct)) {
                    Log.e(TAG, "Failed to initialize AAC decoder (ct=$ct)")
                    return
                }
            }
            CT_PCM -> {
                // No decoder needed — PCM is written directly
            }
            else -> {
                Log.w(TAG, "Unknown codec type: $ct, audio will not play")
            }
        }

        // Create AudioTrack
        val minBufSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufSize, spf * 4 * 4) // at least 4 frames worth

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            // Fallback for older API levels
            @Suppress("DEPRECATION")
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
        }

        audioTrack?.let { track ->
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack not initialized, state=${track.state}")
                releaseAudioTrack()
                return
            }
            // Apply the current AirPlay volume gain before playback starts.
            // If ducked by audio focus, apply the ducked gain.
            val effectiveGain = if (focusDucked) airplayGain * 0.3f else airplayGain
            track.setVolume(effectiveGain)
            requestAudioFocus()
            track.play()
            running = true
            Log.i(TAG, "AudioTrack started: ${SAMPLE_RATE}Hz stereo 16-bit, buf=$bufferSize bytes, ct=$ct, gain=$effectiveGain")
        }
    }

    /**
     * Initialize MediaCodec AAC decoder (for AAC-ELD or AAC-LC).
     * Raw AAC frames from AirPlay (no ADTS headers).
     */
    private fun initAacDecoder(ct: Int): Boolean {
        return try {
            val csd = if (ct == CT_AAC_ELD) AAC_ELD_CSD else AAC_LC_CSD
            val format = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC)
                setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
                setInteger(MediaFormat.KEY_IS_ADTS, 0) // raw AAC, not ADTS
                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            }

            aacDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            aacDecoder?.configure(format, null, null, 0)
            aacDecoder?.start()
            Log.i(TAG, "AAC MediaCodec decoder started: ct=$ct (${if (ct == CT_AAC_ELD) "ELD" else "LC"})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init AAC MediaCodec decoder", e)
            false
        }
    }

    /**
     * Called from onAudioData callback to feed audio data.
     * Routes to the appropriate decoder based on codec type.
     */
    @Synchronized
    fun feedAudio(data: ByteArray, ct: Int, ntpTime: Long, seqnum: Int) {
        if (!running || audioTrack == null) return

        val track = audioTrack ?: return

        when (ct) {
            CT_PCM -> {
                // Raw PCM 16-bit LE, 44100Hz, stereo — write directly
                track.write(data, 0, data.size)
            }
            CT_ALAC -> {
                if (alacHandle == 0L) {
                    Log.w(TAG, "ALAC data received but decoder not initialized")
                    return
                }
                val pcm = nativeBridge.decodeAlac(alacHandle, data)
                if (pcm != null && pcm.isNotEmpty()) {
                    track.write(pcm, 0, pcm.size)
                }
            }
            CT_AAC_ELD, CT_AAC_LC -> {
                feedAacFrame(data, track, ntpTime)
            }
            else -> {
                Log.w(TAG, "Unsupported codec type: $ct, skipping audio packet")
            }
        }
    }

    /**
     * Feed a raw AAC frame to MediaCodec and write decoded PCM to AudioTrack.
     */
    private fun feedAacFrame(data: ByteArray, track: AudioTrack, ptsUs: Long) {
        val decoder = aacDecoder ?: run {
            Log.w(TAG, "AAC data received but decoder not initialized")
            return
        }

        try {
            // Queue input buffer
            val inputId = decoder.dequeueInputBuffer(5000)
            if (inputId >= 0) {
                decoder.getInputBuffer(inputId)?.let { buf ->
                    buf.clear()
                    buf.put(data)
                    decoder.queueInputBuffer(inputId, 0, data.size, ptsUs, 0)
                }
            }

            // Drain output buffers (non-blocking, limited iterations)
            val info = MediaCodec.BufferInfo()
            var iterations = 0
            while (iterations < MAX_DECODE_LOOP) {
                val outputId = decoder.dequeueOutputBuffer(info, 0)
                when {
                    outputId >= 0 -> {
                        decoder.getOutputBuffer(outputId)?.let { buf ->
                            val remaining = buf.remaining()
                            if (remaining > 0) {
                                if (decodeBuffer.size < remaining) {
                                    decodeBuffer = ByteArray(remaining)
                                }
                                buf.get(decodeBuffer, 0, remaining)
                                track.write(decodeBuffer, 0, remaining)
                            }
                        }
                        decoder.releaseOutputBuffer(outputId, false)
                    }
                    outputId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.i(TAG, "AAC output format: ${decoder.outputFormat}")
                    }
                    else -> break // no more output available
                }
                iterations++
            }
        } catch (e: Exception) {
            Log.e(TAG, "AAC decode error", e)
        }
    }

    /**
     * Called when the AirPlay connection is torn down.
     * Stops playback and releases resources but keeps the player ready for next session.
     */
    @Synchronized
    fun stop() {
        Log.i(TAG, "Stopping audio playback")
        running = false
        audioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
                track.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioTrack", e)
            }
        }
        releaseAlacDecoder()
        releaseAacDecoder()
        currentCt = -1
        spf = 0
    }

    /**
     * Full release — called when the service is destroyed.
     */
    @Synchronized
    fun release() {
        Log.i(TAG, "Releasing audio player")
        running = false
        releaseAudioTrack()
        releaseAlacDecoder()
        releaseAacDecoder()
        currentCt = -1
        spf = 0
    }

    /**
     * Set the AirPlay audio volume gain (0.0-1.0).
     * This controls ONLY the AudioTrack's own gain — it does NOT modify
     * the Android system media volume. The system volume remains at
     * whatever the user set on the device.
     *
     * Called from AirCastService.onVolumeChange() when Mac sends volume.
     * The gain is stored so it can be re-applied when AudioTrack is recreated.
     */
    @Synchronized
    fun setVolume(gain: Float) {
        val clamped = gain.coerceIn(0.0f, 1.0f)
        airplayGain = clamped
        // If ducked by audio focus, apply duck multiplier
        val effectiveGain = if (focusDucked) clamped * 0.3f else clamped
        audioTrack?.setVolume(effectiveGain)
        Log.i(TAG, "AirPlay volume gain set: $clamped (effective=$effectiveGain, ducked=$focusDucked)")
    }

    /** Flush audio buffers (e.g., on connection reset). */
    @Synchronized
    fun flush() {
        try {
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing AudioTrack", e)
        }
    }

    private fun releaseAudioTrack() {
        audioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioTrack", e)
            }
        }
        audioTrack = null
        abandonAudioFocus()
    }

    private fun releaseAlacDecoder() {
        if (alacHandle != 0L) {
            try {
                nativeBridge.destroyAlacDecoder(alacHandle)
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying ALAC decoder", e)
            }
            alacHandle = 0L
        }
    }

    private fun releaseAacDecoder() {
        aacDecoder?.let { dec ->
            try {
                dec.stop()
                dec.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AAC decoder", e)
            }
        }
        aacDecoder = null
    }

    private fun requestAudioFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            Log.w(TAG, "Audio focus LOSS — pausing")
                            running = false
                            audioTrack?.apply { pause(); flush() }
                            abandonAudioFocus()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            Log.w(TAG, "Audio focus TRANSIENT LOSS — ducking")
                            focusDucked = true
                            // Duck: multiply AirPlay gain by 0.3
                            audioTrack?.setVolume(airplayGain * 0.3f)
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            Log.i(TAG, "Audio focus GAIN — restoring")
                            if (focusDucked) {
                                focusDucked = false
                                // Restore to the stored AirPlay gain
                                audioTrack?.setVolume(airplayGain)
                            }
                        }
                    }
                }
                .build()
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        running = false
                        audioTrack?.apply { pause(); flush() }
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
