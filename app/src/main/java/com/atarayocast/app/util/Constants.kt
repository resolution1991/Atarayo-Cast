package com.atarayocast.app.util

object Constants {
    const val NOTIFICATION_CHANNEL_ID = "aircast_service"
    const val NOTIFICATION_ID = 1

    // Service actions
    const val ACTION_START = "com.atarayocast.app.action.START"
    const val ACTION_STOP = "com.atarayocast.app.action.STOP"
    const val ACTION_STATE_UPDATE = "com.atarayocast.app.action.STATE_UPDATE"

    // Extras
    const val EXTRA_STATE = "state"
    const val EXTRA_DEVICE_NAME = "device_name"
    const val EXTRA_PROTOCOL = "protocol"

    // Broadcast
    const val BROADCAST_STATE = "com.atarayocast.app.broadcast.STATE"

    // Default ports
    const val AIRPLAY_PORT = 7100
    const val RAOP_PORT = 7000
    const val RTP_VIDEO_PORT = 6000
    const val RTP_AUDIO_PORT = 6001
    const val DLNA_PORT = 1900
    const val DLNA_HTTP_PORT = 8090

    // Prefs keys
    const val PREFS_NAME = "aircast_prefs"
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_RESOLUTION = "resolution"
    const val KEY_H265_ENABLED = "h265_enabled"
    const val KEY_FORCE_H265_ONLY = "force_h265_only"
    const val KEY_PIN_ENABLED = "pin_enabled"
    const val KEY_PIN_CODE = "pin_code"
    const val KEY_BOOT_START = "boot_start"
    const val KEY_PIP_ENABLED = "pip_enabled"
    const val KEY_DEBUG_OVERLAY = "debug_overlay"
    const val KEY_KEEP_SCREEN_ON = "keep_screen_on"        // Phase 3
    const val KEY_ADAPTIVE_RESOLUTION = "adaptive_resolution" // Phase 3
    const val KEY_FULLSCREEN_DEFAULT = "fullscreen_default"  // Phase 3

    // Connection states
    enum class ConnectionState {
        IDLE,
        WAITING,
        CONNECTED,
        STREAMING
    }

    // Protocols
    enum class Protocol {
        AIRPLAY,
        DLNA
    }

    // Resolutions — comprehensive list with common aspect ratios.
    // Manual mode does NOT cap at device resolution; the sender can output any
    // resolution MediaCodec supports.
    //
    // FPS is auto-computed: 30fps for 4K+, 60fps otherwise.
    enum class Resolution(
        val key: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val aspectLabel: String  // human-readable aspect ratio label
    ) {
        AUTO("auto", 0, 0, 60, "自动"),
        P4K_2160("3840x2160", 3840, 2160, 30, "16:9"),
        P2560_1600("2560x1600", 2560, 1600, 60, "16:10"),
        P2560_1440("2560x1440", 2560, 1440, 60, "16:9"),
        P2160_1350("2160x1350", 2160, 1350, 60, "16:10"),
        P1920_1200("1920x1200", 1920, 1200, 60, "16:10"),
        P1920_1080("1920x1080", 1920, 1080, 60, "16:9"),
        P1080_675("1080x675", 1080, 675, 60, "16:10"),
        P1280_720("1280x720", 1280, 720, 60, "16:9");

        /** Display label: "2160×1350 (16:10, 60fps)" */
        val displayLabel: String
            get() = if (this == AUTO) "自动 (原生分辨率)"
            else "${width}×${height} ($aspectLabel, ${fps}fps)"

        companion object {
            fun fromKey(key: String): Resolution =
                entries.find { it.key == key } ?: AUTO
        }
    }

    // Control overlay auto-hide delay (ms)
    const val CONTROL_AUTO_HIDE_DELAY_MS = 8000L
}
