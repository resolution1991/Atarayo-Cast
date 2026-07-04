package com.atarayocast.app.dlna

import android.util.Log
import java.io.*
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight HTTP server for UPnP/DLNA.
 * Handles:
 * - Device/service description XML (GET)
 * - SOAP action handling (POST /xxx/control)
 * - GENA event subscriptions (SUBSCRIBE/UNSUBSCRIBE)
 */
class UpnpHttpServer(
    private val mediaPlayer: DlnaMediaPlayer,
    private val friendlyName: String
) {
    companion object {
        private const val TAG = "DLNA_HTTP"
        private const val CONNECTION_BACKLOG = 10

        // DLNA-compliant sink protocol info
        const val sinkProtocolInfo: String =
            "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_BL_CIF15_AAC_520;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000," +
            "http-get:*:video/x-matroska:*,http-get:*:video/webm:*," +
            "http-get:*:audio/mpeg:*,http-get:*:audio/aac:*," +
            "http-get:*:video/mpeg:*,http-get:*:video/mp2t:*," +
            "http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_SM," +
            "http-get:*:image/png:DLNA.ORG_PN=PNG_LRG"
    }

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    var port: Int = 0
        private set

    // State
    private var volume: Int = 50
    private var mute: Boolean = false
    private var currentUri: String = ""
    private var transportState: String = "STOPPED"
    private var eventSeq: Int = 0

    fun start(preferredPort: Int = 0): Int {
        if (running) return port
        return try {
            serverSocket = ServerSocket(preferredPort, CONNECTION_BACKLOG).apply {
                reuseAddress = true
            }
            port = serverSocket!!.localPort
            running = true

            Thread({
                acceptLoop()
            }, "UPnP-HTTP").apply {
                isDaemon = true
                start()
            }

            Log.i(TAG, "UPnP HTTP server started on port $port")
            port
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UPnP HTTP server", e)
            0
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) { Log.w(TAG, "Error closing server socket", e) }
        serverSocket = null
        clientPool.shutdownNow()
        Log.i(TAG, "UPnP HTTP server stopped")
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val client = serverSocket?.accept() ?: break
                if (activeConnections.get() >= maxConnections) {
                    Log.w(TAG, "Max connections ($maxConnections) reached, dropping connection")
                    try { client.close() } catch (_: Exception) {}
                    continue
                }
                activeConnections.incrementAndGet()
                clientPool.execute {
                    try {
                        handleClient(client)
                    } finally {
                        activeConnections.decrementAndGet()
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Accept error", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 10000
                val input = BufferedReader(InputStreamReader(s.getInputStream()))
                val output = BufferedOutputStream(s.getOutputStream())

                // Read request line and headers
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0]
                val path = URLDecoder.decode(parts[1], "UTF-8")

                // Read headers
                val headers = mutableMapOf<String, String>()
                var line = input.readLine()
                while (!line.isNullOrEmpty()) {
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon).trim().uppercase()
                        val value = line.substring(colon + 1).trim()
                        headers[key] = value
                    }
                    line = input.readLine()
                }

                // Read body if present
                val contentLength = headers["CONTENT-LENGTH"]?.toIntOrNull() ?: 0
                val body = if (contentLength > 0) {
                    val bodyChars = CharArray(contentLength)
                    var totalRead = 0
                    while (totalRead < contentLength) {
                        val read = input.read(bodyChars, totalRead, contentLength - totalRead)
                        if (read < 0) break
                        totalRead += read
                    }
                    String(bodyChars, 0, totalRead)
                } else ""

                when {
                    method == "SUBSCRIBE" -> handleSubscribe(output, path, headers)
                    method == "UNSUBSCRIBE" -> handleUnsubscribe(output, path, headers)
                    method == "GET" -> handleGet(output, path)
                    method == "POST" -> handlePost(output, path, body, headers)
                    else -> sendEmptyResponse(output, 405, "Method Not Allowed")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Client handling error", e)
        }
    }

    // ---- GENA Event Subscription ----

    private val subscribers = ConcurrentHashMap<String, Subscriber>()
    private val clientPool = Executors.newCachedThreadPool { r ->
        Thread(r, "UPnP-Client").apply { isDaemon = true }
    }
    private val activeConnections = AtomicInteger(0)
    private val maxConnections = 50

    data class Subscriber(
        val sid: String,
        val callbackUrl: String,
        val path: String
    )

    private fun handleSubscribe(output: BufferedOutputStream, path: String, headers: Map<String, String>) {
        val callback = headers["CALLBACK"] ?: ""
        val timeout = headers["TIMEOUT"] ?: "Second-1800"

        if (callback.isBlank()) {
            // Renewal
            val sid = headers["SID"] ?: ""
            val sub = subscribers[sid]
            if (sub != null) {
                sendResponse(output, 200, "OK",
                    mapOf("SID" to sid, "TIMEOUT" to timeout), "")
            } else {
                sendEmptyResponse(output, 412, "Precondition Failed")
            }
            return
        }

        // New subscription
        val sid = "uuid:${java.util.UUID.randomUUID()}"
        // Extract callback URL from <http://host:port/path>
        val cbUrl = callback.removeSurrounding("<", ">")
        subscribers[sid] = Subscriber(sid, cbUrl, path)

        Log.i(TAG, "GENA SUBSCRIBE: sid=$sid callback=$cbUrl path=$path")
        sendResponse(output, 200, "OK",
            mapOf(
                "SID" to sid,
                "TIMEOUT" to timeout,
                "SERVER" to "${android.os.Build.MODEL} UPnP/1.0 AirCast/1.0"
            ), "")
    }

    private fun handleUnsubscribe(output: BufferedOutputStream, path: String, headers: Map<String, String>) {
        val sid = headers["SID"] ?: ""
        subscribers.remove(sid)
        Log.i(TAG, "GENA UNSUBSCRIBE: sid=$sid")
        sendEmptyResponse(output, 200, "OK")
    }

    // ---- GET Handler ----

    private fun handleGet(output: BufferedOutputStream, path: String) {
        val body = when {
            path == "/description.xml" || path == "/" ->
                UpnpXmlBuilder.buildDeviceDescription(friendlyName)
            path == "/ConnectionManager/desc.xml" ->
                UpnpXmlBuilder.buildConnectionManagerScpd()
            path == "/AVTransport/desc.xml" ->
                UpnpXmlBuilder.buildAVTransportScpd()
            path == "/RenderingControl/desc.xml" ->
                UpnpXmlBuilder.buildRenderingControlScpd()
            else -> {
                sendEmptyResponse(output, 404, "Not Found")
                return
            }
        }
        sendResponse(output, 200, "OK",
            mapOf("Content-Type" to "text/xml; charset=utf-8"), body)
    }

    // ---- POST (SOAP) Handler ----

    private fun handlePost(output: BufferedOutputStream, path: String, body: String, headers: Map<String, String>) {
        // Verify SOAP action header
        val soapAction = headers["SOAPACTION"] ?: ""
        if (!soapAction.contains("#")) {
            sendEmptyResponse(output, 400, "Bad Request")
            return
        }

        val actionName = soapAction.substringAfter("#").trim('"')

        val responseBody = when {
            path == "/ConnectionManager/control" -> handleConnectionManager(actionName, body)
            path == "/AVTransport/control" -> handleAVTransport(actionName, body)
            path == "/RenderingControl/control" -> handleRenderingControl(actionName, body)
            else -> buildSoapFault(401, "Invalid service")
        }

        val httpStatus = if (responseBody.contains("<faultcode>")) 500 else 200
        val httpMsg = if (responseBody.contains("<faultcode>")) "Internal Server Error" else "OK"

        sendResponse(output, httpStatus, httpMsg,
            mapOf(
                "Content-Type" to "text/xml; charset=utf-8",
                "EXT" to ""
            ), responseBody)

        // After state-changing actions, notify subscribers via GENA
        if (httpStatus == 200 && (path == "/AVTransport/control" || path == "/RenderingControl/control")) {
            notifySubscribers(path.removeSuffix("/control"))
        }
    }

    // ---- ConnectionManager Actions ----

    private fun handleConnectionManager(action: String, body: String): String {
        return when (action) {
            "GetProtocolInfo" -> buildSoapResponse(action) {
                appendArg("Source", sinkProtocolInfo)
                appendArg("Sink", sinkProtocolInfo)
            }
            "GetCurrentConnectionIDs" -> buildSoapResponse(action) {
                appendArg("ConnectionIDs", "0")
            }
            "GetCurrentConnectionInfo" -> buildSoapResponse(action) {
                appendArg("RcsID", "-1")
                appendArg("AVTransportID", "0")
                appendArg("ProtocolInfo", "")
                appendArg("PeerConnectionManager", "/")
                appendArg("PeerConnectionID", "-1")
                appendArg("Direction", "Input")
                appendArg("Status", "OK")
            }
            else -> buildSoapFault(401, "Unknown action: $action")
        }
    }

    // ---- AVTransport Actions ----

    private fun handleAVTransport(action: String, body: String): String {
        return when (action) {
            "SetAVTransportURI" -> {
                val uri = extractArg(body, "CurrentURI")
                val metadata = extractArg(body, "CurrentURIMetaData")
                Log.i(TAG, "SetAVTransportURI: $uri")
                currentUri = uri
                transportState = "TRANSITIONING"
                mediaPlayer.setUrl(uri, "")
                buildSoapResponse(action) {}
            }
            "Play" -> {
                Log.i(TAG, "AVTransport: Play")
                mediaPlayer.play()
                transportState = "PLAYING"
                buildSoapResponse(action) {}
            }
            "Pause" -> {
                Log.i(TAG, "AVTransport: Pause")
                mediaPlayer.pause()
                transportState = "PAUSED_PLAYBACK"
                buildSoapResponse(action) {}
            }
            "Stop" -> {
                Log.i(TAG, "AVTransport: Stop")
                mediaPlayer.stop()
                transportState = "STOPPED"
                buildSoapResponse(action) {}
            }
            "Seek" -> {
                val unit = extractArg(body, "Unit")
                val target = extractArg(body, "Target")
                Log.i(TAG, "AVTransport: Seek unit=$unit target=$target")
                val ms = parseSeekTarget(unit, target)
                mediaPlayer.seekTo(ms)
                buildSoapResponse(action) {}
            }
            "GetPositionInfo" -> buildSoapResponse(action) {
                val posMs = mediaPlayer.getCurrentPosition()
                val durMs = mediaPlayer.getDuration()
                appendArg("Track", if (currentUri.isNotEmpty()) "1" else "0")
                appendArg("TrackDuration", formatDuration(durMs))
                appendArg("TrackMetaData", "")
                appendArg("TrackURI", currentUri)
                appendArg("RelTime", formatDuration(posMs))
                appendArg("AbsTime", "NOT_IMPLEMENTED")
                appendArg("RelCount", "2147483647")
                appendArg("AbsCount", "2147483647")
            }
            "GetTransportInfo" -> buildSoapResponse(action) {
                val state = if (currentUri.isEmpty()) "NO_MEDIA_PRESENT" else transportState
                appendArg("CurrentTransportState", state)
                appendArg("CurrentTransportStatus", "OK")
                appendArg("CurrentSpeed", "1")
            }
            "GetMediaInfo" -> buildSoapResponse(action) {
                appendArg("NrTracks", if (currentUri.isNotEmpty()) "1" else "0")
                appendArg("MediaDuration", formatDuration(mediaPlayer.getDuration()))
                appendArg("CurrentURI", currentUri)
                appendArg("CurrentURIMetaData", "")
                appendArg("NextURI", "")
                appendArg("NextURIMetaData", "")
                appendArg("PlayMedium", "NETWORK")
                appendArg("RecordMedium", "NOT_IMPLEMENTED")
                appendArg("WriteStatus", "NOT_IMPLEMENTED")
            }
            "GetTransportSettings" -> buildSoapResponse(action) {
                appendArg("PlayMode", "NORMAL")
                appendArg("RecQualityMode", "NOT_IMPLEMENTED")
            }
            "Next", "Previous" -> buildSoapResponse(action) {}
            else -> buildSoapFault(401, "Unknown action: $action")
        }
    }

    // ---- RenderingControl Actions ----

    private fun handleRenderingControl(action: String, body: String): String {
        return when (action) {
            "SetVolume" -> {
                val desired = extractArg(body, "DesiredVolume").toIntOrNull() ?: 50
                volume = desired.coerceIn(0, 100)
                mediaPlayer.setVolume(volume)
                Log.i(TAG, "SetVolume: $volume")
                buildSoapResponse(action) {}
            }
            "GetVolume" -> buildSoapResponse(action) {
                appendArg("CurrentVolume", volume.toString())
            }
            "SetMute" -> {
                mute = extractArg(body, "DesiredMute") == "1" || extractArg(body, "DesiredMute").equals("true", ignoreCase = true)
                mediaPlayer.setMute(mute)
                Log.i(TAG, "SetMute: $mute")
                buildSoapResponse(action) {}
            }
            "GetMute" -> buildSoapResponse(action) {
                appendArg("CurrentMute", if (mute) "1" else "0")
            }
            else -> buildSoapFault(401, "Unknown action: $action")
        }
    }

    // ---- SOAP Helpers ----

    private fun buildSoapResponse(action: String, block: StringBuilder.() -> Unit): String {
        val result = StringBuilder()
        block(result)
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:${action}Response xmlns:u="urn:schemas-upnp-org:service:${getServiceTypeFromAction(action)}">
${result}
</u:${action}Response>
</s:Body>
</s:Envelope>"""
    }

    private fun buildSoapFault(code: Int, description: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<s:Fault>
<faultcode>s:Client</faultcode>
<faultstring>UPnPError</faultstring>
<detail>
<UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
<errorCode>$code</errorCode>
<errorDescription>$description</errorDescription>
</UPnPError>
</detail>
</s:Fault>
</s:Body>
</s:Envelope>"""
    }

    private fun StringBuilder.appendArg(name: String, value: String) {
        append("<$name>")
        append(xmlEscape(value))
        append("</$name>\n")
    }

    private fun xmlEscape(s: String): String {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
    }

    private fun extractArg(xml: String, argName: String): String {
        val open = "<$argName>"
        val close = "</$argName>"
        val start = xml.indexOf(open)
        if (start < 0) return ""
        val end = xml.indexOf(close, start)
        if (end < 0) return ""
        return xml.substring(start + open.length, end)
    }

    private fun getServiceTypeFromAction(action: String): String = when {
        listOf("GetProtocolInfo", "GetCurrentConnectionIDs", "GetCurrentConnectionInfo").contains(action) ->
            "ConnectionManager:1"
        listOf("SetVolume", "GetVolume", "SetMute", "GetMute").contains(action) ->
            "RenderingControl:1"
        else -> "AVTransport:1"
    }

    // ---- GENA Notifications ----

    private fun notifySubscribers(path: String) {
        if (subscribers.isEmpty()) return

        // Determine which service this path belongs to and build the event XML
        val serviceType = when {
            path.contains("AVTransport") -> "AVTransport"
            path.contains("RenderingControl") -> "RenderingControl"
            else -> return
        }

        val lastChange = buildLastChangeXml(serviceType)
        val eventBody = buildPropertySetXml(lastChange)

        val seq = eventSeq++
        val subsCopy = subscribers.values.filter { it.path.contains(serviceType) }

        Log.d(TAG, "Notifying ${subsCopy.size} subscribers for $serviceType (seq=$seq)")

        for (sub in subsCopy) {
            clientPool.execute { sendNotify(sub, seq, eventBody) }
        }
    }

    private fun buildLastChangeXml(serviceType: String): String {
        val eventXml = when (serviceType) {
            "AVTransport" -> {
                """<Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/">""" +
                """<InstanceID val="0">""" +
                """<TransportState val="$transportState"/>""" +
                """<TransportStatus val="OK"/>""" +
                """<CurrentMediaDuration val="${formatDuration(mediaPlayer.getDuration())}"/>""" +
                """<AVTransportURI val="$currentUri"/>""" +
                """</InstanceID></Event>"""
            }
            "RenderingControl" -> {
                """<Event xmlns="urn:schemas-upnp-org:metadata-1-0/RCS/">""" +
                """<InstanceID val="0">""" +
                """<Volume val="$volume" channel="Master"/>""" +
                """<Mute val="${if (mute) "1" else "0"}" channel="Master"/>""" +
                """</InstanceID></Event>"""
            }
            else -> ""
        }
        // XML-encode the inner XML as it goes into LastChange text content
        return eventXml
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun buildPropertySetXml(lastChange: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>""" + "\n" +
               """<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">""" +
               """<e:property><LastChange>$lastChange</LastChange></e:property>""" +
               """</e:propertyset>"""
    }

    private fun sendNotify(sub: Subscriber, seq: Int, body: String) {
        try {
            val url = URL(sub.callbackUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "NOTIFY"
            conn.doOutput = true
            conn.setRequestProperty("CONTENT-TYPE", "text/xml; charset=utf-8")
            conn.setRequestProperty("NT", "upnp:event")
            conn.setRequestProperty("NTS", "upnp:propchange")
            conn.setRequestProperty("SID", sub.sid)
            conn.setRequestProperty("SEQ", seq.toString())
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            Log.d(TAG, "NOTIFY to ${sub.callbackUrl} -> $responseCode (sid=${sub.sid})")
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send GENA NOTIFY to ${sub.callbackUrl}: ${e.message}")
        }
    }

    // ---- HTTP Helpers ----

    private fun sendResponse(
        output: BufferedOutputStream,
        status: Int,
        msg: String,
        headers: Map<String, String>,
        body: String
    ) {
        try {
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder()
            sb.append("HTTP/1.1 $status $msg\r\n")
            sb.append("Content-Length: ${bodyBytes.size}\r\n")
            headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            if (!headers.containsKey("Connection")) {
                sb.append("Connection: close\r\n")
            }
            sb.append("\r\n")

            output.write(sb.toString().toByteArray(Charsets.UTF_8))
            output.write(bodyBytes)
            output.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Error sending response", e)
        }
    }

    private fun sendEmptyResponse(output: BufferedOutputStream, status: Int, msg: String) {
        sendResponse(output, status, msg, emptyMap(), "")
    }

    // ---- Util ----

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "00:00:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun parseSeekTarget(unit: String, target: String): Long {
        return try {
            when (unit.uppercase()) {
                "REL_TIME" -> {
                    val parts = target.split(":")
                    if (parts.size == 3) {
                        (parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toDouble().toLong()) * 1000
                    } else 0
                }
                "TRACK_NR" -> 0
                else -> target.toLongOrNull() ?: 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse seek target: $unit=$target", e)
            0
        }
    }
}
