package com.atarayocast.app.dlna

import java.util.UUID

/**
 * Builds UPnP/SSDP XML documents for DLNA MediaRenderer.
 * Device description, service SCPDs, and SOAP envelopes.
 */
object UpnpXmlBuilder {

    val deviceUdn: String = "uuid:${UUID.randomUUID()}"

    // Configuration
    private const val MANUFACTURER = "AirCast"
    private const val MANUFACTURER_URL = "https://github.com/aircast"
    private const val MODEL_NAME = "AirCast DLNA Renderer"
    private const val MODEL_NUMBER = "1.0"
    private const val MODEL_URL = "https://github.com/aircast"
    private const val SERIAL_NUMBER = "AC-DLNA-001"

    // Service types
    const val SERVICE_TYPE_CM = "urn:schemas-upnp-org:service:ConnectionManager:1"
    const val SERVICE_TYPE_AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val SERVICE_TYPE_RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val SERVICE_ID_CM = "urn:upnp-org:serviceId:ConnectionManager"
    const val SERVICE_ID_AVT = "urn:upnp-org:serviceId:AVTransport"
    const val SERVICE_ID_RCS = "urn:upnp-org:serviceId:RenderingControl"

    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"

    // These are set per-instance based on server info
    var host: String = ""
    var httpPort: Int = 0

    fun serviceUrl(service: String): String =
        "http://$host:$httpPort/$service"

    fun controlUrl(service: String): String =
        "http://$host:$httpPort/$service/control"

    fun eventUrl(service: String): String =
        "http://$host:$httpPort/$service/event"

    /**
     * Build the device description XML.
     * This is what DLNA controllers fetch via the Location header in SSDP.
     */
    fun buildDeviceDescription(friendlyName: String): String {
        val now = System.currentTimeMillis() / 1000
        return """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <URLBase>http://$host:$httpPort</URLBase>
  <device>
    <deviceType>$DEVICE_TYPE</deviceType>
    <friendlyName>${xmlEscape(friendlyName)}</friendlyName>
    <manufacturer>$MANUFACTURER</manufacturer>
    <manufacturerURL>$MANUFACTURER_URL</manufacturerURL>
    <modelDescription>AirCast DLNA Media Renderer for Android</modelDescription>
    <modelName>$MODEL_NAME</modelName>
    <modelNumber>$MODEL_NUMBER</modelNumber>
    <modelURL>$MODEL_URL</modelURL>
    <serialNumber>$SERIAL_NUMBER</serialNumber>
    <UDN>$deviceUdn</UDN>
    <serviceList>
      <service>
        <serviceType>$SERVICE_TYPE_CM</serviceType>
        <serviceId>$SERVICE_ID_CM</serviceId>
        <SCPDURL>/${"ConnectionManager"}/desc.xml</SCPDURL>
        <controlURL>/${"ConnectionManager"}/control</controlURL>
        <eventSubURL>/${"ConnectionManager"}/event</eventSubURL>
      </service>
      <service>
        <serviceType>$SERVICE_TYPE_AVT</serviceType>
        <serviceId>$SERVICE_ID_AVT</serviceId>
        <SCPDURL>/${"AVTransport"}/desc.xml</SCPDURL>
        <controlURL>/${"AVTransport"}/control</controlURL>
        <eventSubURL>/${"AVTransport"}/event</eventSubURL>
      </service>
      <service>
        <serviceType>$SERVICE_TYPE_RCS</serviceType>
        <serviceId>$SERVICE_ID_RCS</serviceId>
        <SCPDURL>/${"RenderingControl"}/desc.xml</SCPDURL>
        <controlURL>/${"RenderingControl"}/control</controlURL>
        <eventSubURL>/${"RenderingControl"}/event</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>"""
    }

    /**
     * Build the ConnectionManager service description (SCPD).
     */
    fun buildConnectionManagerScpd(): String = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetProtocolInfo</name>
      <argumentList>
        <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
        <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetCurrentConnectionIDs</name>
      <argumentList>
        <argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetCurrentConnectionInfo</name>
      <argumentList>
        <argument><name>ConnectionID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>
        <argument><name>RcsID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_RcsID</relatedStateVariable></argument>
        <argument><name>AVTransportID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_AVTransportID</relatedStateVariable></argument>
        <argument><name>ProtocolInfo</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ProtocolInfo</relatedStateVariable></argument>
        <argument><name>PeerConnectionManager</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionManager</relatedStateVariable></argument>
        <argument><name>PeerConnectionID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>
        <argument><name>Direction</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Direction</relatedStateVariable></argument>
        <argument><name>Status</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionStatus</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionID</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_RcsID</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_AVTransportID</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionManager</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Direction</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionStatus</name><dataType>string</dataType>
      <allowedValueList><allowedValue>OK</allowedValue><allowedValue>ContentFormatMismatch</allowedValue><allowedValue>InsufficientBandwidth</allowedValue><allowedValue>UnreliableChannel</allowedValue><allowedValue>Unknown</allowedValue></allowedValueList>
    </stateVariable>
  </serviceStateTable>
</scpd>"""

    /**
     * Build the AVTransport service description (SCPD).
     */
    fun buildAVTransportScpd(): String = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>SetAVTransportURI</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
        <argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>Play</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>Pause</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>Stop</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>Seek</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>
        <argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>Next</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>Previous</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetPositionInfo</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument>
        <argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>
        <argument><name>TrackMetaData</name><direction>out</direction><relatedStateVariable>CurrentTrackMetaData</relatedStateVariable></argument>
        <argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>CurrentTrackURI</relatedStateVariable></argument>
        <argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument>
        <argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>AbsoluteTimePosition</relatedStateVariable></argument>
        <argument><name>RelCount</name><direction>out</direction><relatedStateVariable>RelativeCounterPosition</relatedStateVariable></argument>
        <argument><name>AbsCount</name><direction>out</direction><relatedStateVariable>AbsoluteCounterPosition</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetTransportInfo</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>
        <argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>
        <argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetMediaInfo</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>NrTracks</name><direction>out</direction><relatedStateVariable>NumberOfTracks</relatedStateVariable></argument>
        <argument><name>MediaDuration</name><direction>out</direction><relatedStateVariable>CurrentMediaDuration</relatedStateVariable></argument>
        <argument><name>CurrentURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
        <argument><name>CurrentURIMetaData</name><direction>out</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
        <argument><name>NextURI</name><direction>out</direction><relatedStateVariable>NextAVTransportURI</relatedStateVariable></argument>
        <argument><name>NextURIMetaData</name><direction>out</direction><relatedStateVariable>NextAVTransportURIMetaData</relatedStateVariable></argument>
        <argument><name>PlayMedium</name><direction>out</direction><relatedStateVariable>PlaybackStorageMedium</relatedStateVariable></argument>
        <argument><name>RecordMedium</name><direction>out</direction><relatedStateVariable>RecordStorageMedium</relatedStateVariable></argument>
        <argument><name>WriteStatus</name><direction>out</direction><relatedStateVariable>RecordMediumWriteStatus</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetTransportSettings</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>PlayMode</name><direction>out</direction><relatedStateVariable>CurrentPlayMode</relatedStateVariable></argument>
        <argument><name>RecQualityMode</name><direction>out</direction><relatedStateVariable>CurrentRecordQualityMode</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>TransportState</name><dataType>string</dataType>
      <allowedValueList><allowedValue>STOPPED</allowedValue><allowedValue>PLAYING</allowedValue><allowedValue>PAUSED_PLAYBACK</allowedValue><allowedValue>TRANSITIONING</allowedValue><allowedValue>NO_MEDIA_PRESENT</allowedValue></allowedValueList>
    </stateVariable>
    <stateVariable sendEvents="no"><name>TransportStatus</name><dataType>string</dataType>
      <allowedValueList><allowedValue>OK</allowedValue><allowedValue>ERROR_OCCURRED</allowedValue></allowedValueList>
    </stateVariable>
    <stateVariable sendEvents="no"><name>TransportPlaySpeed</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentMediaDuration</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>NextAVTransportURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>NextAVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrackMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrackURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>RelativeCounterPosition</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AbsoluteCounterPosition</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>NumberOfTracks</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentPlayMode</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentRecordQualityMode</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>PlaybackStorageMedium</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>RecordStorageMedium</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>RecordMediumWriteStatus</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType>
      <allowedValueList><allowedValue>TRACK_NR</allowedValue><allowedValue>REL_TIME</allowedValue><allowedValue>ABS_TIME</allowedValue></allowedValueList>
    </stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""

    /**
     * Build the RenderingControl service description (SCPD).
     */
    fun buildRenderingControlScpd(): String = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>SetVolume</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetVolume</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>SetMute</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>DesiredMute</name><direction>in</direction><relatedStateVariable>Mute</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetMute</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>CurrentMute</name><direction>out</direction><relatedStateVariable>Mute</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="yes"><name>Volume</name><dataType>ui2</dataType>
      <allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange>
    </stateVariable>
    <stateVariable sendEvents="yes"><name>Mute</name><dataType>boolean</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Channel</name><dataType>string</dataType>
      <allowedValueList><allowedValue>Master</allowedValue></allowedValueList>
    </stateVariable>
  </serviceStateTable>
</scpd>"""

    /**
     * Parse an XML tag content between <tag> and </tag>.
     */
    fun extractTag(xml: String, tag: String): String {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return ""
        val end = xml.indexOf(close, start)
        if (end < 0) return ""
        return xml.substring(start + open.length, end)
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
