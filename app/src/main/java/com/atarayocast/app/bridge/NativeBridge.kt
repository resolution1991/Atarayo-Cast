package com.atarayocast.app.bridge

import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI bridge to the native UxPlay AirPlay engine.
 *
 * Manages native handle lifecycle, frame buffer pool (DirectByteBuffer reuse
 * eliminating ~120MB/s per-frame allocations), codec configuration,
 * and DNS-SD TXT records for mDNS registration.
 *
 * Video frames flow: native → DirectByteBuffer → onVideoData → decoder → returnFrameBuffer
 */
class NativeBridge {

    companion object {
        private const val TAG = "NativeBridge"
        private const val POOL_SIZE = 8
        private const val BUFFER_SIZE = 4 * 1024 * 1024  // 4 MB

        init {
            System.loadLibrary("aircast_native")
        }
    }

    private var nativeHandle: Long = 0L
    private val initialized = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    // ---- Lifecycle ----

    fun initialize(
        callback: NativeCallbacks,
        hwAddr: ByteArray,
        name: String,
        keyFile: String,
        nohold: Boolean = true,
        requirePin: Boolean = false
    ): Boolean {
        if (initialized.get()) {
            Log.w(TAG, "Already initialized")
            return true
        }
        nativeHandle = nativeInit(callback, hwAddr, name, keyFile, nohold, requirePin)
        if (nativeHandle == 0L) {
            Log.e(TAG, "nativeInit() returned 0")
            return false
        }

        // Initialize frame buffer pool: allocate DirectByteBuffers and register with native
        val buffers = Array(POOL_SIZE) { ByteBuffer.allocateDirect(BUFFER_SIZE) }
        nativeInitFramePool(nativeHandle, buffers)
        Log.i(TAG, "Native bridge initialized, handle=$nativeHandle, pool=${POOL_SIZE}x${BUFFER_SIZE / 1024 / 1024}MB")

        initialized.set(true)
        return true
    }

    fun start(requestedPort: Int = 7000): Int {
        if (!initialized.get()) {
            Log.e(TAG, "Not initialized")
            return -1
        }
        if (running.get()) {
            Log.w(TAG, "Already running")
            return -1
        }
        val port = nativeStart(nativeHandle, requestedPort)
        if (port >= 0) {
            running.set(true)
            Log.i(TAG, "Native server started on port $port")
        } else {
            Log.e(TAG, "nativeStart() failed")
        }
        return port
    }

    fun stop() {
        if (!initialized.get()) return
        if (running.get()) {
            nativeStop(nativeHandle)
            running.set(false)
            Log.i(TAG, "Native server stopped")
        }
    }

    fun destroy() {
        stop()
        if (initialized.get()) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
            initialized.set(false)
            Log.i(TAG, "Native bridge destroyed")
        }
    }

    // ---- Configuration ----

    fun setDisplaySize(width: Int, height: Int, fps: Int) {
        if (!initialized.get()) return
        nativeSetDisplaySize(nativeHandle, width, height, fps)
    }

    fun setH265Enabled(enabled: Boolean) {
        if (!initialized.get()) return
        nativeSetH265Enabled(nativeHandle, enabled)
    }

    fun setCodecs(alac: Boolean, aac: Boolean) {
        if (!initialized.get()) return
        nativeSetCodecs(nativeHandle, alac, aac)
    }

    fun setPinAuth(enabled: Boolean, pin: String) {
        if (!initialized.get()) return
        nativeSetPinAuth(nativeHandle, enabled, pin)
    }

    fun setPlist(key: String, value: Int) {
        if (!initialized.get()) return
        nativeSetPlist(nativeHandle, key, value)
    }

    // ---- Frame Buffer Pool ----

    /**
     * Return a DirectByteBuffer to the native frame pool after the decoder
     * has consumed its contents. Called from VideoDecoder handler thread.
     */
    fun returnFrameBuffer(buffer: ByteBuffer) {
        if (!initialized.get()) return
        nativeReturnFrameBuffer(nativeHandle, buffer)
    }

    // ---- DNS-SD TXT record accessors ----

    fun getRaopTxtRecords(): HashMap<String, String>? {
        if (!initialized.get()) return null
        return nativeGetRaopTxtRecords(nativeHandle)
    }

    fun getAirplayTxtRecords(): HashMap<String, String>? {
        if (!initialized.get()) return null
        return nativeGetAirplayTxtRecords(nativeHandle)
    }

    fun getRaopServiceName(): String? {
        if (!initialized.get()) return null
        return nativeGetRaopServiceName(nativeHandle)
    }

    fun getServerName(): String? {
        if (!initialized.get()) return null
        return nativeGetServerName(nativeHandle)
    }

    // ---- ALAC software decoder ----

    fun initAlacDecoder(
        frameLength: Int, numChannels: Int, bitDepth: Int,
        pb: Int, mb: Int, kb: Int
    ): Long = nativeAlacInit(frameLength, numChannels, bitDepth, pb, mb, kb)

    fun decodeAlac(handle: Long, input: ByteArray): ByteArray? = nativeAlacDecode(handle, input)

    fun destroyAlacDecoder(handle: Long) { nativeAlacDestroy(handle) }

    // ---- State ----

    val isRunning: Boolean get() = running.get()
    val isInitialized: Boolean get() = initialized.get()

    // ---- JNI declarations ----

    private external fun nativeInit(
        callback: NativeCallbacks, hwAddr: ByteArray, name: String,
        keyFile: String, nohold: Boolean, requirePin: Boolean
    ): Long

    private external fun nativeStart(handle: Long, requestedPort: Int): Int
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetDisplaySize(handle: Long, width: Int, height: Int, fps: Int)
    private external fun nativeSetH265Enabled(handle: Long, enabled: Boolean)
    private external fun nativeSetCodecs(handle: Long, alac: Boolean, aac: Boolean)
    private external fun nativeSetPinAuth(handle: Long, enabled: Boolean, pin: String)
    private external fun nativeSetPlist(handle: Long, key: String, value: Int)
    private external fun nativeGetRaopTxtRecords(handle: Long): HashMap<String, String>?
    private external fun nativeGetAirplayTxtRecords(handle: Long): HashMap<String, String>?
    private external fun nativeGetRaopServiceName(handle: Long): String?
    private external fun nativeGetServerName(handle: Long): String?
    private external fun nativeInitFramePool(handle: Long, buffers: Array<ByteBuffer>)
    private external fun nativeReturnFrameBuffer(handle: Long, buffer: ByteBuffer)
    private external fun nativeAlacInit(
        frameLength: Int, numChannels: Int, bitDepth: Int,
        pb: Int, mb: Int, kb: Int
    ): Long
    private external fun nativeAlacDecode(handle: Long, input: ByteArray): ByteArray?
    private external fun nativeAlacDestroy(handle: Long)
}
