package com.atarayocast.app.bridge

import java.nio.ByteBuffer

/**
 * Callback interface invoked from the native UxPlay engine via JNI.
 *
 * All methods are called from native threads (RAOP's internal pthreads),
 * so implementations must handle thread safety. The native layer calls
 * these methods by name via JNI GetMethodID, so signatures must match exactly.
 */
interface NativeCallbacks {

    /**
     * Called when a video frame is received from the AirPlay sender.
     * The [data] ByteBuffer is a DirectByteBuffer from the frame buffer pool.
     * The implementation MUST call [NativeBridge.returnFrameBuffer] after
     * the frame has been consumed by the decoder to return the buffer to the pool.
     */
    fun onVideoData(data: ByteBuffer, ntpTime: Long, isH265: Boolean)

    /** Called when audio data is received from the AirPlay sender. */
    fun onAudioData(data: ByteArray, ct: Int, ntpTime: Long, seqnum: Int)

    /** Called when audio format information is available. */
    fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean)

    /** Called when the video dimensions are reported. */
    fun onVideoSize(widthSrc: Float, heightSrc: Float, width: Float, height: Float)

    /** Called when the sender requests a volume change (0.0 - 1.0). */
    fun onVolumeChange(volume: Float)

    /** Called when a client connects. */
    fun onConnectionInit()

    /** Called when a client disconnects. */
    fun onConnectionDestroy()

    /** Called when the connection is reset (e.g., resolution change). */
    fun onConnectionReset(reason: Int)

    /** Called to display a PIN code for authentication. */
    fun onDisplayPin(pin: String)

    /** Called with track metadata (binary plist). */
    fun onMetadata(data: ByteArray)

    /** Called with cover art image data. */
    fun onCoverArt(data: ByteArray)

    /** Called with playback progress info. */
    fun onProgress(start: Long, curr: Long, end: Long)

    /** Called with DACP remote control identifiers. */
    fun onDacpId(dacpId: String, activeRemote: String)

    /** Called when audio-only mode changes (no video mirror). */
    fun onAudioOnly(audioOnly: Boolean)

    /** Called when video streaming is paused. */
    fun onVideoPause()

    /** Called when video streaming resumes after pause. */
    fun onVideoResume()

    /** Called when video decoder should be reset (e.g., codec change). */
    fun onVideoReset(reason: Int)

    /** Called when audio buffer should be flushed. */
    fun onAudioFlush()

    /** Called when video buffer should be flushed. */
    fun onVideoFlush()

    /** Called when force-H.265 mode rejects a sender-selected video codec. */
    fun onUnsupportedVideoCodec(codec: Int)
}
