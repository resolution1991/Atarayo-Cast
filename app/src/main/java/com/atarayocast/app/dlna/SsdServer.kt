package com.atarayocast.app.dlna

import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * UPnP SSDP (Simple Service Discovery Protocol) server.
 * Listens for M-SEARCH requests on multicast and responds with
 * NOTIFY messages to advertise the MediaRenderer device.
 */
class SsdServer(
    private val friendlyName: String,
    private val httpPort: Int
) {
    companion object {
        private const val TAG = "DLNA_SSDP"
        private const val SSDP_MULTICAST = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val MAX_PACKET_SIZE = 4096
    }

    @Volatile
    private var running = false
    private var socket: MulticastSocket? = null
    private var thread: Thread? = null
    private var notifyThread: Thread? = null

    // UUID that identifies this specific device instance
    private val usnRoot = "${UpnpXmlBuilder.deviceUdn}::upnp:rootdevice"
    private val usnDevice = "${UpnpXmlBuilder.deviceUdn}::${UpnpXmlBuilder.DEVICE_TYPE}"

    fun start(): Boolean {
        if (running) return true
        return try {
            socket = MulticastSocket(SSDP_PORT).apply {
                reuseAddress = true
                // Bind to all interfaces; join the SSDP multicast group
                val group = InetAddress.getByName(SSDP_MULTICAST)
                joinGroup(group)
                timeToLive = 4
            }

            running = true

            // Thread 1: Listen for M-SEARCH
            thread = Thread({
                listenLoop()
            }, "SSDP-Listen").apply {
                isDaemon = true
                start()
            }

            // Thread 2: Periodic NOTIFY (alive messages)
            notifyThread = Thread({
                try {
                    // Send initial NOTIFY burst
                    repeat(3) {
                        if (!running) return@Thread
                        sendAlive()
                        Thread.sleep(100)
                    }
                    // Then every 60 seconds
                    while (running) {
                        Thread.sleep(60_000)
                        if (running) sendAlive()
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }, "SSDP-Notify").apply {
                isDaemon = true
                start()
            }

            Log.i(TAG, "SSDP server started on $SSDP_MULTICAST:$SSDP_PORT")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SSDP server", e)
            stop()
            false
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        notifyThread?.interrupt()
        try {
            socket?.close()
        } catch (e: Exception) { Log.w(TAG, "Error closing SSDP socket", e) }
        socket = null
        Log.i(TAG, "SSDP server stopped")
    }

    private fun listenLoop() {
        val buf = ByteArray(MAX_PACKET_SIZE)
        val packet = DatagramPacket(buf, buf.size)

        while (running) {
            try {
                socket?.receive(packet)
                if (!running) break
                val data = String(packet.data, 0, packet.length)
                processPacket(data, packet.address, packet.port)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Error receiving SSDP packet", e)
            }
        }
    }

    private fun processPacket(data: String, remoteAddr: InetAddress, remotePort: Int) {
        // Check if it's M-SEARCH
        if (!data.startsWith("M-SEARCH")) return

        // Extract ST (Search Target) header
        val st = extractHeader(data, "ST") ?: return
        val man = extractHeader(data, "MAN") ?: ""
        if (!man.contains("ssdp:discover", ignoreCase = true)) return

        Log.v(TAG, "M-SEARCH from ${remoteAddr.hostAddress}:$remotePort ST=$st")

        // Determine correct USN based on ST
        val responseUsn: String
        val responseSt: String

        when {
            st == "ssdp:all" -> {
                // Respond for root device
                sendSearchResponse(remoteAddr, remotePort, usnRoot, "upnp:rootdevice")
                sendSearchResponse(remoteAddr, remotePort, usnDevice, UpnpXmlBuilder.DEVICE_TYPE)
                sendSearchResponse(remoteAddr, remotePort,
                    "${UpnpXmlBuilder.deviceUdn}::${UpnpXmlBuilder.SERVICE_TYPE_AVT}",
                    UpnpXmlBuilder.SERVICE_TYPE_AVT)
                return
            }
            st == "upnp:rootdevice" -> {
                responseUsn = usnRoot
                responseSt = "upnp:rootdevice"
            }
            st == UpnpXmlBuilder.DEVICE_TYPE -> {
                responseUsn = usnDevice
                responseSt = UpnpXmlBuilder.DEVICE_TYPE
            }
            st == UpnpXmlBuilder.SERVICE_TYPE_AVT -> {
                responseUsn = "${UpnpXmlBuilder.deviceUdn}::${UpnpXmlBuilder.SERVICE_TYPE_AVT}"
                responseSt = UpnpXmlBuilder.SERVICE_TYPE_AVT
            }
            st == UpnpXmlBuilder.SERVICE_TYPE_CM -> {
                responseUsn = "${UpnpXmlBuilder.deviceUdn}::${UpnpXmlBuilder.SERVICE_TYPE_CM}"
                responseSt = UpnpXmlBuilder.SERVICE_TYPE_CM
            }
            st == UpnpXmlBuilder.SERVICE_TYPE_RCS -> {
                responseUsn = "${UpnpXmlBuilder.deviceUdn}::${UpnpXmlBuilder.SERVICE_TYPE_RCS}"
                responseSt = UpnpXmlBuilder.SERVICE_TYPE_RCS
            }
            else -> {
                Log.d(TAG, "Unrecognized ST: $st")
                return
            }
        }

        sendSearchResponse(remoteAddr, remotePort, responseUsn, responseSt)
    }

    private fun sendSearchResponse(
        addr: InetAddress,
        port: Int,
        usn: String,
        st: String
    ) {
        try {
            val location = "http://${getLocalAddress()}:${httpPort}/description.xml"
            val maxAge = 1800

            val response = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("CACHE-CONTROL: max-age=$maxAge\r\n")
                append("DATE: ${java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).format(java.util.Date())}\r\n")
                append("EXT:\r\n")
                append("LOCATION: $location\r\n")
                append("SERVER: ${android.os.Build.MODEL} UPnP/1.0 AirCast/1.0\r\n")
                append("ST: $st\r\n")
                append("USN: $usn\r\n")
                append("\r\n")
            }

            val data = response.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(data, data.size, addr, port)
            socket?.send(packet)

            Log.v(TAG, "M-SEARCH response sent to ${addr.hostAddress}:$port USN=$usn")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send M-SEARCH response", e)
        }
    }

    private fun sendAlive() {
        try {
            val location = "http://${getLocalAddress()}:${httpPort}/description.xml"
            val maxAge = 1800
            val server = "${android.os.Build.MODEL} UPnP/1.0 AirCast/1.0"
            val ntHeaders = listOf(
                "upnp:rootdevice" to usnRoot,
                UpnpXmlBuilder.DEVICE_TYPE to usnDevice,
                UpnpXmlBuilder.SERVICE_TYPE_AVT to "${UpnpXmlBuilder.deviceUdn}::${UpnpXmlBuilder.SERVICE_TYPE_AVT}",
                UpnpXmlBuilder.SERVICE_TYPE_CM to "${UpnpXmlBuilder.deviceUdn}::${UpnpXmlBuilder.SERVICE_TYPE_CM}",
                UpnpXmlBuilder.SERVICE_TYPE_RCS to "${UpnpXmlBuilder.deviceUdn}::${UpnpXmlBuilder.SERVICE_TYPE_RCS}"
            )

            for ((nt, usn) in ntHeaders) {
                val notify = buildString {
                    append("NOTIFY * HTTP/1.1\r\n")
                    append("HOST: $SSDP_MULTICAST:$SSDP_PORT\r\n")
                    append("CACHE-CONTROL: max-age=$maxAge\r\n")
                    append("LOCATION: $location\r\n")
                    append("SERVER: $server\r\n")
                    append("NT: $nt\r\n")
                    append("NTS: ssdp:alive\r\n")
                    append("USN: $usn\r\n")
                    append("\r\n")
                }
                val data = notify.toByteArray(Charsets.UTF_8)
                val group = InetAddress.getByName(SSDP_MULTICAST)
                val packet = DatagramPacket(data, data.size, group, SSDP_PORT)
                socket?.send(packet)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send NOTIFY alive", e)
        }
    }

    /**
     * Send ssdp:byebye NOTIFY messages before shutdown.
     */
    fun sendByeBye() {
        try {
            val ntHeaders = listOf(
                "upnp:rootdevice" to usnRoot,
                UpnpXmlBuilder.DEVICE_TYPE to usnDevice
            )
            for ((nt, usn) in ntHeaders) {
                val bye = buildString {
                    append("NOTIFY * HTTP/1.1\r\n")
                    append("HOST: $SSDP_MULTICAST:$SSDP_PORT\r\n")
                    append("NT: $nt\r\n")
                    append("NTS: ssdp:byebye\r\n")
                    append("USN: $usn\r\n")
                    append("\r\n")
                }
                val data = bye.toByteArray(Charsets.UTF_8)
                val group = InetAddress.getByName(SSDP_MULTICAST)
                val packet = DatagramPacket(data, data.size, group, SSDP_PORT)
                socket?.send(packet)
            }
        } catch (e: Exception) { Log.w(TAG, "Error closing SSDP socket", e) }
    }

    private fun extractHeader(http: String, name: String): String? {
        val pattern = Regex("^$name:\\s*(.+)$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        return pattern.find(http)?.groupValues?.get(1)?.trim()
    }

    private fun getLocalAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    // Return IPv4 address that's not loopback
                    val host = addr.hostAddress
                    if (host != null && host.indexOf('.') > 0
                        && !addr.isLoopbackAddress) {
                        return host
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "Error closing SSDP socket", e) }
        return "0.0.0.0"
    }
}
