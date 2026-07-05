package com.atarayocast.app.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import android.view.Surface
import androidx.media3.ui.PlayerView
import java.net.NetworkInterface
import java.util.UUID

/**
 * Top-level manager for the DLNA MediaRenderer.
 * Coordinates SSDP discovery (SsdServer) and SOAP/HTTP (UpnpHttpServer)
 * with media playback (DlnaMediaPlayer).
 *
 * No external dependencies — pure Android APIs.
 */
class DlnaManager(
    private val context: Context,
    private val onStateChange: (Boolean) -> Unit,
    private val onTransportStateChange: (DlnaMediaPlayer.TransportState) -> Unit = {}
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
    private var pendingSurface: Surface? = null
    private var pendingPlayerView: PlayerView? = null
    private var isRunning = false

    var friendlyName: String = "AirCast-DLNA"
        private set

    fun start(deviceName: String = friendlyName): Boolean {
        if (isRunning) {
            Log.w(TAG, "Already running")
            return true
        }

        friendlyName = deviceName.ifBlank { "AirCast-DLNA" }
        UpnpXmlBuilder.deviceUdn = buildStableDeviceUdn()

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
                onStateChange = {
                    Log.d(TAG, "Transport state: $it")
                    httpServer?.notifyTransportChanged()
                    onTransportStateChange(it)
                },
                onPositionUpdate = { pos, dur -> Log.v(TAG, "Position: $pos / $dur") },
                onError = {
                    Log.e(TAG, "Player error: $it")
                    httpServer?.notifyTransportChanged()
                }
            ).also {
                it.init()
                it.setSurface(pendingSurface)
                it.setPlayerView(pendingPlayerView)
            }

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

    fun stopPlayback() {
        mediaPlayer?.stop()
    }

    fun stopPlaybackFromReceiver() {
        httpServer?.stopPlaybackFromReceiver() ?: mediaPlayer?.stop()
    }

    fun setSurface(surface: Surface?) {
        pendingSurface = surface
        mediaPlayer?.setSurface(surface)
        Log.i(TAG, "Surface ${if (surface != null) "set" else "cleared"} for DLNA player")
    }

    fun setPlayerView(playerView: PlayerView?) {
        pendingPlayerView = playerView
        mediaPlayer?.setPlayerView(playerView)
        Log.i(TAG, "PlayerView ${if (playerView != null) "set" else "cleared"} for DLNA player")
    }

    private fun getLocalAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            val preferred = interfaces.sortedWith(
                compareBy<NetworkInterface> {
                    when {
                        it.name.startsWith("wlan") -> 0
                        it.name.startsWith("ap") -> 1
                        it.name.startsWith("eth") -> 2
                        else -> 3
                    }
                }.thenBy { it.name }
            )
            for (iface in preferred) {
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

    private fun buildStableDeviceUdn(): String {
        return try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "atarayo-cast"
            "uuid:${UUID.nameUUIDFromBytes("atarayo-dlna-$androidId".toByteArray())}"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build stable UDN", e)
            UpnpXmlBuilder.deviceUdn
        }
    }
}
