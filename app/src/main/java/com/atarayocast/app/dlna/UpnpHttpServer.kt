package com.atarayocast.app.dlna

import android.util.Log
import java.io.*
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
            "http-get:*:video/mp4:*,http-get:*:video/x-m4v:*," +
            "http-get:*:video/x-matroska:*,http-get:*:video/webm:*," +
            "http-get:*:video/quicktime:*,http-get:*:video/3gpp:*," +
            "http-get:*:video/x-msvideo:*,http-get:*:video/x-flv:*," +
            "http-get:*:video/mpeg:*,http-get:*:video/mp2t:*," +
            "http-get:*:audio/mpeg:*,http-get:*:audio/mp4:*,http-get:*:audio/aac:*," +
            "http-get:*:audio/x-m4a:*,http-get:*:audio/flac:*,http-get:*:audio/wav:*," +
            "http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_SM," +
            "http-get:*:image/png:DLNA.ORG_PN=PNG_LRG," +
            "http-get:*:video/*:*,http-get:*:audio/*:*"
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
                val output = BufferedOutputStream(s.getOutputStream())
                val request = readHttpRequest(s.getInputStream()) ?: return

                when {
                    request.method == "SUBSCRIBE" -> handleSubscribe(output, request.path, request.headers)
                    request.method == "UNSUBSCRIBE" -> handleUnsubscribe(output, request.path, request.headers)
                    request.method == "GET" -> handleGet(output, request.path)
                    request.method == "HEAD" -> handleHead(output, request.path)
                    request.method == "POST" -> handlePost(output, request.path, request.body, request.headers)
                    else -> sendEmptyResponse(output, 405, "Method Not Allowed")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Client handling error", e)
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    private fun readHttpRequest(input: InputStream): HttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        val delimiter = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        var matched = 0

        while (matched < delimiter.size) {
            val b = input.read()
            if (b < 0) return null
            headerBytes.write(b)
            matched = if (b.toByte() == delimiter[matched]) {
                matched + 1
            } else if (b == '\r'.code) {
                1
            } else {
                0
            }
            if (headerBytes.size() > 64 * 1024) {
                Log.w(TAG, "HTTP headers too large")
                return null
            }
        }

        val headerText = String(headerBytes.toByteArray(), Charsets.ISO_8859_1)
        val headerLines = headerText.split("\r\n")
        val requestLine = headerLines.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null

        val headers = mutableMapOf<String, String>()
        for (line in headerLines.drop(1)) {
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().uppercase()] = line.substring(colon + 1).trim()
            }
        }

        val contentLength = headers["CONTENT-LENGTH"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val bodyBytes = ByteArray(contentLength)
        var total = 0
        while (total < contentLength) {
            val read = input.read(bodyBytes, total, contentLength - total)
            if (read < 0) break
            total += read
        }

        return HttpRequest(
            method = parts[0].uppercase(),
            path = URLDecoder.decode(parts[1], "UTF-8"),
            headers = headers,
            body = String(bodyBytes, 0, total, Charsets.UTF_8)
        )
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

        val serviceType = when {
            path.contains("AVTransport") -> "AVTransport"
            path.contains("RenderingControl") -> "RenderingControl"
            else -> null
        }
        if (serviceType != null) {
            val initialBody = buildPropertySetXml(buildLastChangeXml(serviceType))
            clientPool.execute { sendNotify(subscribers[sid] ?: return@execute, 0, initialBody) }
        }
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

    private fun handleHead(output: BufferedOutputStream, path: String) {
        val exists = path == "/description.xml" || path == "/" ||
            path == "/ConnectionManager/desc.xml" ||
            path == "/AVTransport/desc.xml" ||
            path == "/RenderingControl/desc.xml"
        if (exists) {
            sendEmptyResponse(output, 200, "OK")
        } else {
            sendEmptyResponse(output, 404, "Not Found")
        }
    }

    // ---- POST (SOAP) Handler ----

    private fun handlePost(output: BufferedOutputStream, path: String, body: String, headers: Map<String, String>) {
        // Verify SOAP action header
        val soapAction = headers["SOAPACTION"] ?: ""
        if (!soapAction.contains("#")) {
            sendEmptyResponse(output, 400, "Bad Request")
            return
        }

        val actionName = soapAction.substringAfter("#").trim('"', ' ', '\t')

        val responseBody = when {
            path.equals("/ConnectionManager/control", ignoreCase = true) -> handleConnectionManager(actionName, body)
            path.equals("/AVTransport/control", ignoreCase = true) -> handleAVTransport(actionName, body)
            path.equals("/RenderingControl/control", ignoreCase = true) -> handleRenderingControl(actionName, body)
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
        if (httpStatus == 200 && (
                path.equals("/AVTransport/control", ignoreCase = true) ||
                path.equals("/RenderingControl/control", ignoreCase = true)
            )
        ) {
            notifySubscribers(path.removeSuffix("/control"))
        }
    }

    // ---- ConnectionManager Actions ----

    private fun handleConnectionManager(action: String, body: String): String {
        return when (action.lowercase()) {
            "getprotocolinfo" -> buildSoapResponse("GetProtocolInfo") {
                appendArg("Source", "")
                appendArg("Sink", sinkProtocolInfo)
            }
            "getcurrentconnectionids" -> buildSoapResponse("GetCurrentConnectionIDs") {
                appendArg("ConnectionIDs", "0")
            }
            "getcurrentconnectioninfo" -> buildSoapResponse("GetCurrentConnectionInfo") {
                appendArg("RcsID", "-1")
                appendArg("AVTransportID", "0")
                appendArg("ProtocolInfo", "")
                appendArg("PeerConnectionManager", "/")
                appendArg("PeerConnectionID", "-1")
                appendArg("Direction", "Input")
                appendArg("Status", "OK")
            }
            "prepareforconnection" -> buildSoapResponse("PrepareForConnection") {
                appendArg("ConnectionID", "0")
                appendArg("AVTransportID", "0")
                appendArg("RcsID", "0")
            }
            "connectioncomplete" -> buildSoapResponse("ConnectionComplete") {}
            else -> buildSoapFault(401, "Unknown action: $action")
        }
    }

    // ---- AVTransport Actions ----

    private fun handleAVTransport(action: String, body: String): String {
        return when (action.lowercase()) {
            "setavtransporturi" -> {
                val uri = extractArg(body, "CurrentURI")
                val metadata = extractArg(body, "CurrentURIMetaData")
                val title = extractTitle(metadata)
                Log.i(TAG, "SetAVTransportURI: $uri title=${title ?: "-"}")
                if (uri.isBlank()) {
                    return buildSoapFault(714, "No such resource")
                }
                currentUri = uri
                mediaPlayer.setUrl(uri, title, autoPlay = true)
                buildSoapResponse("SetAVTransportURI") {}
            }
            "setnextavtransporturi" -> buildSoapResponse("SetNextAVTransportURI") {}
            "play" -> {
                Log.i(TAG, "AVTransport: Play")
                mediaPlayer.play()
                buildSoapResponse("Play") {}
            }
            "pause" -> {
                Log.i(TAG, "AVTransport: Pause")
                mediaPlayer.pause()
                buildSoapResponse("Pause") {}
            }
            "stop" -> {
                Log.i(TAG, "AVTransport: Stop")
                mediaPlayer.stop()
                currentUri = ""
                buildSoapResponse("Stop") {}
            }
            "seek" -> {
                val unit = extractArg(body, "Unit")
                val target = extractArg(body, "Target")
                Log.i(TAG, "AVTransport: Seek unit=$unit target=$target")
                val ms = parseSeekTarget(unit, target)
                mediaPlayer.seekTo(ms)
                buildSoapResponse("Seek") {}
            }
            "getpositioninfo" -> buildSoapResponse("GetPositionInfo") {
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
            "gettransportinfo" -> buildSoapResponse("GetTransportInfo") {
                val state = if (currentUri.isEmpty()) {
                    "NO_MEDIA_PRESENT"
                } else {
                    mediaPlayer.getTransportState().name
                }
                appendArg("CurrentTransportState", state)
                appendArg("CurrentTransportStatus", mediaPlayer.getTransportStatus())
                appendArg("CurrentSpeed", "1")
            }
            "getmediainfo" -> buildSoapResponse("GetMediaInfo") {
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
            "getdevicecapabilities" -> buildSoapResponse("GetDeviceCapabilities") {
                appendArg("PlayMedia", "NETWORK")
                appendArg("RecMedia", "NOT_IMPLEMENTED")
                appendArg("RecQualityModes", "NOT_IMPLEMENTED")
            }
            "getcurrenttransportactions" -> buildSoapResponse("GetCurrentTransportActions") {
                appendArg("Actions", if (currentUri.isEmpty()) "Play" else "Play,Stop,Pause,Seek")
            }
            "setplaymode" -> buildSoapResponse("SetPlayMode") {}
            "gettransportsettings" -> buildSoapResponse("GetTransportSettings") {
                appendArg("PlayMode", "NORMAL")
                appendArg("RecQualityMode", "NOT_IMPLEMENTED")
            }
            "next" -> buildSoapResponse("Next") {}
            "previous" -> buildSoapResponse("Previous") {}
            else -> buildSoapFault(401, "Unknown action: $action")
        }
    }

    // ---- RenderingControl Actions ----

    private fun handleRenderingControl(action: String, body: String): String {
        return when (action.lowercase()) {
            "listpresets" -> buildSoapResponse("ListPresets") {
                appendArg("CurrentPresetNameList", "FactoryDefaults")
            }
            "selectpreset" -> buildSoapResponse("SelectPreset") {}
            "setvolume" -> {
                val desired = extractArg(body, "DesiredVolume").toIntOrNull() ?: 50
                volume = desired.coerceIn(0, 100)
                mediaPlayer.setVolume(volume)
                Log.i(TAG, "SetVolume: $volume")
                buildSoapResponse("SetVolume") {}
            }
            "getvolume" -> buildSoapResponse("GetVolume") {
                appendArg("CurrentVolume", volume.toString())
            }
            "setmute" -> {
                mute = extractArg(body, "DesiredMute") == "1" || extractArg(body, "DesiredMute").equals("true", ignoreCase = true)
                mediaPlayer.setMute(mute)
                Log.i(TAG, "SetMute: $mute")
                buildSoapResponse("SetMute") {}
            }
            "getmute" -> buildSoapResponse("GetMute") {
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
        val pattern = Regex(
            "<(?:[A-Za-z0-9_\\-]+:)?$argName(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_\\-]+:)?$argName>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        return xmlUnescape(pattern.find(xml)?.groupValues?.get(1)?.trim() ?: "")
    }

    private fun getServiceTypeFromAction(action: String): String = when {
        listOf(
            "GetProtocolInfo",
            "GetCurrentConnectionIDs",
            "GetCurrentConnectionInfo",
            "PrepareForConnection",
            "ConnectionComplete"
        ).contains(action) ->
            "ConnectionManager:1"
        listOf(
            "ListPresets",
            "SelectPreset",
            "SetVolume",
            "GetVolume",
            "SetMute",
            "GetMute"
        ).contains(action) ->
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

    fun notifyTransportChanged() {
        notifySubscribers("/AVTransport")
    }

    fun stopPlaybackFromReceiver() {
        Log.i(TAG, "Receiver requested AVTransport stop")
        currentUri = ""
        mediaPlayer.stop()
        notifyTransportChanged()
    }

    private fun buildLastChangeXml(serviceType: String): String {
        val state = if (currentUri.isEmpty()) {
            "NO_MEDIA_PRESENT"
        } else {
            mediaPlayer.getTransportState().name
        }
        val eventXml = when (serviceType) {
            "AVTransport" -> {
                """<Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/">""" +
                """<InstanceID val="0">""" +
                """<TransportState val="$state"/>""" +
                """<TransportStatus val="${mediaPlayer.getTransportStatus()}"/>""" +
                """<CurrentMediaDuration val="${formatDuration(mediaPlayer.getDuration())}"/>""" +
                """<AVTransportURI val="$currentUri"/>""" +
                """<RelativeTimePosition val="${formatDuration(mediaPlayer.getCurrentPosition())}"/>""" +
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
            val host = url.host
            val port = if (url.port > 0) url.port else url.defaultPort.takeIf { it > 0 } ?: 80
            val path = url.file.ifBlank { "/" }
            val bodyBytes = body.toByteArray(Charsets.UTF_8)

            Socket(host, port).use { socket ->
                socket.soTimeout = 5000
                val request = buildString {
                    append("NOTIFY $path HTTP/1.1\r\n")
                    append("HOST: $host:$port\r\n")
                    append("CONTENT-TYPE: text/xml; charset=utf-8\r\n")
                    append("NT: upnp:event\r\n")
                    append("NTS: upnp:propchange\r\n")
                    append("SID: ${sub.sid}\r\n")
                    append("SEQ: $seq\r\n")
                    append("Content-Length: ${bodyBytes.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                val output = BufferedOutputStream(socket.getOutputStream())
                output.write(request.toByteArray(Charsets.UTF_8))
                output.write(bodyBytes)
                output.flush()

                val statusLine = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                Log.d(TAG, "NOTIFY to ${sub.callbackUrl} -> ${statusLine ?: "no response"} (sid=${sub.sid})")
            }
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

    private fun extractTitle(metadata: String): String? {
        if (metadata.isBlank()) return null
        val pattern = Regex(
            "<(?:[A-Za-z0-9_\\-]+:)?title(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_\\-]+:)?title>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        return xmlUnescape(pattern.find(metadata)?.groupValues?.get(1)?.trim() ?: "").ifBlank { null }
    }

    private fun xmlUnescape(s: String): String {
        return s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}
