package com.atarayocast.app.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import android.view.Surface

/**
 * Top-level manager for the DLNA MediaRenderer.
 * Coordinates SSDP discovery (SsdServer) and SOAP/HTTP (UpnpHttpServer)
 * with media playback (DlnaMediaPlayer).
 *
 * No external dependencies — pure Android APIs.
 */
class DlnaManager(
    private val context: Context,
    private val onStateChange: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "DlnaManager"
        private const val DEFAULT_HTTP_PORT = 8090
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
    }

    private var mediaPlayer: DlnaMediaPlayer? = null
    private var httpServer: UpnpHttpServer? = null
    private var ssdServer: SsdServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var isRunning = false

    val friendlyName: String = "AirCast-DLNA"

    fun start(): Boolean {
        if (isRunning) {
            Log.w(TAG, "Already running")
            return true
        }

        var attempt = 0
        while (attempt < MAX_RETRIES) {
            attempt++
            if (doStart()) {
                Log.i(TAG, "DLNA started on attempt $attempt")
                return true
            }
            Log.w(TAG, "DLNA start attempt $attempt/$MAX_RETRIES failed, retrying...")
            if (attempt < MAX_RETRIES) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
        Log.e(TAG, "DLNA Manager failed after $MAX_RETRIES attempts")
        return false
    }

    private fun doStart(): Boolean {
        stop() // clean up any partial state from previous attempt
        try {
            // Acquire multicast lock for SSDP
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("AirCast:DLNA").apply {
                setReferenceCounted(true)
                acquire()
            }

            // 1) Create media player
            mediaPlayer = DlnaMediaPlayer(
                appContext = context.applicationContext,
                onStateChange = { Log.d(TAG, "Transport state: $it") },
                onPositionUpdate = { pos, dur -> Log.v(TAG, "Position: $pos / $dur") },
                onError = { Log.e(TAG, "Player error: $it") }
            ).also { it.init() }

            // 2) Start HTTP server (UPnP XML + SOAP)
            httpServer = UpnpHttpServer(mediaPlayer!!, friendlyName)
            val httpPort = httpServer!!.start(DEFAULT_HTTP_PORT)
            if (httpPort <= 0) {
                Log.e(TAG, "HTTP server failed to start")
                stop()
                return false
            }

            // 3) Configure XML builder with actual host/port
            UpnpXmlBuilder.httpPort = httpPort
            UpnpXmlBuilder.host = getLocalAddress()

            // 4) Start SSDP discovery
            ssdServer = SsdServer(friendlyName, httpPort)
            if (!ssdServer!!.start()) {
                Log.e(TAG, "SSDP server failed to start")
                stop()
                return false
            }

            isRunning = true
            onStateChange(true)
            Log.i(TAG, "DLNA Renderer running: HTTP=$httpPort, name=$friendlyName")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DLNA Manager", e)
            stop()
            return false
        }
    }

    fun stop() {
        isRunning = false
        ssdServer?.sendByeBye()
        ssdServer?.stop()
        httpServer?.stop()
        mediaPlayer?.destroy()
        multicastLock?.let {
            if (it.isHeld) it.release()
        }

        ssdServer = null
        httpServer = null
        mediaPlayer = null
        multicastLock = null

        onStateChange(false)
        Log.i(TAG, "DLNA Manager stopped")
    }

    fun getMediaPlayer(): DlnaMediaPlayer? = mediaPlayer
    fun isRunning(): Boolean = isRunning

    fun setSurface(surface: Surface?) {
        mediaPlayer?.setSurface(surface)
        Log.i(TAG, "Surface ${if (surface != null) "set" else "cleared"} for DLNA player")
    }

    private fun getLocalAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    val host = addr.hostAddress
                    if (host != null && host.indexOf('.') > 0 &&
                        !addr.isLoopbackAddress) {
                        return host
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get local address", e)
        }
        return "127.0.0.1"
    }
}
