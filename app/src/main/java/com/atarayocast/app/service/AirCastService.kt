package com.atarayocast.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.media.AudioManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.atarayocast.app.MainActivity
import com.atarayocast.app.R
import com.atarayocast.app.audio.AudioPlayer
import com.atarayocast.app.bridge.NativeBridge
import com.atarayocast.app.bridge.NativeCallbacks
import com.atarayocast.app.data.AppPrefs
import com.atarayocast.app.dlna.DlnaManager
import com.atarayocast.app.util.Constants
import com.atarayocast.app.video.VideoDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import android.os.Binder

class AirCastService : Service(), NativeCallbacks {

    companion object {
        private const val TAG = "AirCastService"
        private const val VIDEO_RESET_FRAME_DROPPED = 1001
        private const val VIDEO_CODEC_H264 = 1

        fun start(context: Context) {
            val intent = Intent(context, AirCastService::class.java).apply {
                action = Constants.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AirCastService::class.java).apply {
                action = Constants.ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val nativeBridge = NativeBridge()
    private val prefs by lazy { AppPrefs(this) }
    private val registrar by lazy { AirPlayRegistrar(this, nativeBridge) }
    private val videoDecoder = VideoDecoder(nativeBridge)
    private val audioPlayer by lazy { AudioPlayer(nativeBridge, this) }
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val dlnaManager by lazy { DlnaManager(this) { running ->
        Log.i(TAG, "DLNA service state changed: running=$running")
    } }
    private var state = Constants.ConnectionState.IDLE
    private var serverPort = 0

    // Debug stats
    @Volatile var debugFps: Int = 0
        private set
    @Volatile var debugBitrate: Float = 0f  // Mbps
        private set
    @Volatile var debugCodec: String = "-"
        private set
    @Volatile var debugResW: Int = 0
        private set
    @Volatile var debugResH: Int = 0
        private set
    private var frameCount = 0L
    private var frameBytes = 0L
    private var lastFpsReset = 0L
    @Volatile private var forceH265OnlyActive = false
    private val unsupportedCodecDisconnectScheduled = AtomicBoolean(false)

    // Phase 3: WakeLock
    private var wakeLock: PowerManager.WakeLock? = null

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): AirCastService = this@AirCastService
        fun setSurface(surface: Surface?) {
            videoDecoder.setSurface(surface)
            dlnaManager.setSurface(surface)
        }
        /**
         * Disconnect the current AirPlay client but keep the service running.
         * Restarts the RAOP HTTP server to sever the Mac's connection at the
         * protocol level, then stops video/audio playback. mDNS stays registered.
         */
        fun disconnectClient() {
            Log.i(TAG, "disconnectClient: restarting HTTPD + stopping playback")
            // Restart RAOP HTTP server — this kicks the current client at the
            // protocol level. The Mac will see the connection drop.
            nativeBridge.restartHttpd(serverPort)
            // Stop local playback
            videoDecoder.stop()
            audioPlayer.stop()
            state = Constants.ConnectionState.WAITING
            updateNotification(getString(R.string.status_waiting))
            broadcastState()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Constants.ACTION_START -> startAirCast()
            Constants.ACTION_STOP -> {
                stopAirCast()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    // ---- Notification channel ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW  // Low = no sound, just persistent presence
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    // ---- AirCast lifecycle ----

    private fun startAirCast() {
        Log.i(TAG, "Starting AirCast service...")
        startForeground(Constants.NOTIFICATION_ID, createNotification(
            getString(R.string.status_idle), null
        ))

        // Reset VideoDecoder in case it was released by a previous onDestroy()
        videoDecoder.resetIfNeeded()

        serviceScope.launch {
            try {
                val deviceName = prefs.deviceName.first()
                val h265Enabled = prefs.h265Enabled.first()
                val forceH265Only = prefs.forceH265Only.first()
                val effectiveH265Enabled = h265Enabled || forceH265Only
                forceH265OnlyActive = forceH265Only
                unsupportedCodecDisconnectScheduled.set(false)
                val pinEnabled = prefs.pinEnabled.first()
                val pinCode = if (pinEnabled) prefs.pinCode.first() else ""
                val keepScreenOn = prefs.keepScreenOn.first()
                val adaptRes = prefs.adaptiveResolution.first()
                val resKey = prefs.resolution.first()

                val hwAddr = generateHwAddr()
                val keyFile = File(filesDir, "aircast_ed25519.key").absolutePath

                if (!nativeBridge.initialize(
                        callback = this@AirCastService,
                        hwAddr = hwAddr,
                        name = deviceName,
                        keyFile = keyFile,
                        nohold = true,
                        requirePin = pinEnabled
                    )) {
                    Log.e(TAG, "Failed to initialize native bridge")
                    updateNotification(getString(R.string.status_idle))
                    return@launch
                }

                nativeBridge.setH265Enabled(effectiveH265Enabled)
                nativeBridge.setForceH265Only(forceH265Only)
                nativeBridge.setCodecs(alac = true, aac = effectiveH265Enabled) // AAC supported via MediaCodec
                Log.i(TAG, "Codec options: h265Enabled=$h265Enabled forceH265Only=$forceH265Only effectiveH265Enabled=$effectiveH265Enabled")

                // Apply custom PIN if enabled (overrides random PIN from init)
                if (pinEnabled && pinCode.isNotEmpty()) {
                    nativeBridge.setPinAuth(true, pinCode)
                    Log.i(TAG, "Custom PIN auth enabled")
                }

                // ---- Resolution: adaptive or manual ----
                val displaySize = if (adaptRes) {
                    val ds = detectDeviceResolution()
                    nativeBridge.setDisplaySize(ds.width, ds.height, ds.fps)
                    Log.i(TAG, "Adaptive resolution: ${ds.width}x${ds.height}@${ds.fps}fps")
                    ds
                } else {
                    val res = Constants.Resolution.fromKey(resKey)
                    if (res.width > 0 && res.height > 0) {
                        // Manual resolution — NOT capped by device resolution.
                        // Sender outputs at requested res; MediaCodec handles scaling.
                        nativeBridge.setDisplaySize(res.width, res.height, res.fps)
                        Log.i(TAG, "Fixed resolution: ${res.key} (${res.width}x${res.height}@${res.fps}fps)")
                        DisplaySize(res.width, res.height, res.fps)
                    } else {
                        // Fallback: AUTO somehow stored in manual mode
                        val ds = detectDeviceResolution()
                        nativeBridge.setDisplaySize(ds.width, ds.height, ds.fps)
                        Log.w(TAG, "AUTO resolution in manual mode — falling back to adaptive: ${ds.width}x${ds.height}@${ds.fps}fps")
                        ds
                    }
                }
                // Prime MediaCodec with the negotiated display size. Some AirPlay
                // sessions deliver the first IDR before the native video-size
                // callback; using the old 1920x1080 fallback can leave high
                // resolutions such as 2560x1600 connected but black.
                videoDecoder.setSize(displaySize.width, displaySize.height)

                serverPort = nativeBridge.start(Constants.RAOP_PORT)
                if (serverPort > 0) {
                    state = Constants.ConnectionState.WAITING
                    updateNotification(getString(R.string.status_waiting))
                    broadcastState()

                    registrar.register(serverPort)
                    dlnaManager.start()

                    // Phase 3: Acquire wake lock if enabled
                    if (keepScreenOn) acquireWakeLock()

                    Log.i(TAG, "AirPlay server started on port $serverPort, mDNS + DLNA registered")
                } else {
                    Log.e(TAG, "Failed to start native server (port=$serverPort)")
                    updateNotification(getString(R.string.status_idle))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting service", e)
                updateNotification(getString(R.string.status_idle))
            }
        }
    }

    private fun stopAirCast() {
        Log.i(TAG, "Stopping AirCast service...")
        releaseWakeLock()
        audioPlayer.stop()
        dlnaManager.stop()
        registrar.unregister()
        videoDecoder.stop()
        nativeBridge.stop()
        nativeBridge.destroy()
        state = Constants.ConnectionState.IDLE
        broadcastState()
    }

    // ---- Phase 3: WakeLock ----

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AirCast:ScreenOn"
            ).apply {
                acquire() // No timeout — held for entire streaming session
            }
            Log.i(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            Log.i(TAG, "WakeLock released")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock", e)
        }
    }

    // ---- Phase 3: Adaptive resolution ----

    /** Simple container for detected display dimensions. */
    private data class DisplaySize(val width: Int, val height: Int, val fps: Int)

    private fun detectDeviceResolution(): DisplaySize {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
        } else {
            wm.defaultDisplay.getRealMetrics(metrics)
        }

        var w = metrics.widthPixels
        var h = metrics.heightPixels
        Log.i(TAG, "Device resolution: ${w}x${h}")

        // Ensure even dimensions (H.264/H.265 requirement)
        w = w and 0x7FFFFFFE // clear LSB
        h = h and 0x7FFFFFFE

        // Cap at 4K for bandwidth/codec limits
        if (w > 3840 || h > 2160) {
            Log.w(TAG, "Resolution too high, capping to 3840x2160")
            w = 3840
            h = 2160
        }

        // Determine fps: 60 for resolutions up to ~2560x1600, 30 for higher
        val fps = if (w * h <= 2560 * 1600) 60 else 30

        return DisplaySize(w, h, fps)
    }

    // ---- NativeCallbacks ----

    override fun onVideoData(data: ByteBuffer, ntpTime: Long, isH265: Boolean) {
        if (forceH265OnlyActive && !isH265) {
            nativeBridge.returnFrameBuffer(data)
            onUnsupportedVideoCodec(VIDEO_CODEC_H264)
            return
        }

        val size = data.limit()
        videoDecoder.decodeFrame(data, isH265, ntpTime)

        // Track debug stats
        frameCount++
        frameBytes += size
        val now = System.currentTimeMillis()
        if (lastFpsReset == 0L) lastFpsReset = now
        val elapsed = now - lastFpsReset
        if (elapsed >= 1000) {
            debugFps = ((frameCount * 1000) / elapsed).toInt()
            debugBitrate = (frameBytes * 8f) / (elapsed / 1000f) / 1_000_000f
            debugCodec = if (isH265) "H.265/HEVC" else "H.264/AVC"
            Log.i(TAG, "Video stats: ${debugFps}fps ${String.format("%.1f", debugBitrate)}Mbps $debugCodec (frames=$frameCount)")
            frameCount = 0
            frameBytes = 0
            lastFpsReset = now
        }

        if (state != Constants.ConnectionState.STREAMING) {
            state = Constants.ConnectionState.STREAMING
            updateNotification(getString(R.string.status_streaming))
            broadcastState()
        }
    }

    override fun onAudioData(data: ByteArray, ct: Int, ntpTime: Long, seqnum: Int) {
        audioPlayer.feedAudio(data, ct, ntpTime, seqnum)
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        Log.i(TAG, "Audio format: ct=$ct, spf=$spf, screen=$usingScreen")
        audioPlayer.onAudioFormat(ct, spf, usingScreen)
    }

    override fun onVideoSize(widthSrc: Float, heightSrc: Float, width: Float, height: Float) {
        Log.i(TAG, "Video size: ${widthSrc}x${heightSrc} -> ${width}x${height}")
        videoDecoder.setSize(width.toInt(), height.toInt())
        debugResW = width.toInt()
        debugResH = height.toInt()
    }

    override fun onVolumeChange(volume: Float) {
        Log.i(TAG, "Volume change: $volume dB")
        // AirPlay sends volume as negative dB attenuation:
        //   0.0 dB = maximum (no attenuation)
        //   -30.0 dB = quiet (Mac slider minimum)
        //   -144.0 dB = muted
        //
        // Convert dB to linear gain (0.0-1.0) for AudioTrack.setVolume().
        // This controls ONLY the AirPlay audio stream's gain — it does NOT
        // touch the Android system media volume. The system volume stays at
        // whatever the user set on the device.
        //
        // 10^(dB/20) is the standard dB-to-linear conversion for audio gain:
        //   0 dB → 1.0 (full volume)
        //   -6 dB → 0.5 (half perceived loudness)
        //   -20 dB → 0.1
        //   -30 dB → 0.032
        //   -144 dB → 0.0 (mute)
        val clampedDb = volume.coerceIn(-144.0f, 0.0f)
        val gain = Math.pow(10.0, clampedDb / 20.0).toFloat()
        audioPlayer.setVolume(gain)
    }

    override fun onConnectionInit() {
        Log.i(TAG, "Client connected")
        frameCount = 0; frameBytes = 0; lastFpsReset = 0
        debugFps = 0; debugBitrate = 0f; debugCodec = "-"; debugResW = 0; debugResH = 0
        unsupportedCodecDisconnectScheduled.set(false)
        state = Constants.ConnectionState.CONNECTED
        updateNotification(getString(R.string.status_connected))
        broadcastState()
    }

    override fun onConnectionDestroy() {
        Log.i(TAG, "Client disconnected")
        audioPlayer.stop()
        videoDecoder.stop()
        state = Constants.ConnectionState.WAITING
        updateNotification(getString(R.string.status_waiting))
        broadcastState()
    }

    override fun onConnectionReset(reason: Int) {
        Log.i(TAG, "Connection reset: reason=$reason")
        audioPlayer.flush()
    }

    override fun onUnsupportedVideoCodec(codec: Int) {
        if (!forceH265OnlyActive) return
        val codecName = if (codec == VIDEO_CODEC_H264) "H.264" else "codec=$codec"
        if (!unsupportedCodecDisconnectScheduled.compareAndSet(false, true)) return

        Log.w(TAG, "Force H.265 only: sender selected $codecName, disconnecting client")
        serviceScope.launch {
            debugFps = 0
            debugBitrate = 0f
            debugCodec = "拒绝 $codecName"
            frameCount = 0
            frameBytes = 0
            lastFpsReset = 0
            videoDecoder.stop()
            audioPlayer.stop()
            if (serverPort > 0 && nativeBridge.isRunning) {
                nativeBridge.restartHttpd(serverPort)
            }
            state = Constants.ConnectionState.WAITING
            updateNotification(getString(R.string.status_waiting))
            broadcastState()
        }
    }

    override fun onDisplayPin(pin: String) {
        Log.i(TAG, "Display PIN: $pin")
        val notif = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.pin_notification_title))
            .setContentText(getString(R.string.pin_notification_text, pin))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(Constants.NOTIFICATION_ID, notif)
    }

    override fun onMetadata(data: ByteArray) { Log.d(TAG, "Metadata: ${data.size} bytes") }
    override fun onCoverArt(data: ByteArray) { Log.d(TAG, "Cover art: ${data.size} bytes") }
    override fun onProgress(start: Long, curr: Long, end: Long) {
        Log.d(TAG, "Progress: start=$start, curr=$curr, end=$end")
    }
    override fun onDacpId(dacpId: String, activeRemote: String) {
        Log.i(TAG, "DACP ID: $dacpId, activeRemote: $activeRemote")
    }
    override fun onAudioOnly(audioOnly: Boolean) {
        Log.i(TAG, "Audio-only mode: $audioOnly")
    }

    override fun onVideoPause() {
        Log.i(TAG, "Video paused")
        videoDecoder.stop()
    }

    override fun onVideoResume() {
        Log.i(TAG, "Video resumed")
    }

    override fun onVideoReset(reason: Int) {
        if (reason == VIDEO_RESET_FRAME_DROPPED) {
            Log.w(TAG, "Video reset requested after dropped reference frame")
        } else {
            Log.i(TAG, "Video reset, reason=$reason")
        }
        // RTP reset or local frame loss: flush pending frames but keep codec alive
        // so it can re-sync when the next IDR frame arrives.
        videoDecoder.flush()
    }

    override fun onAudioFlush() {
        Log.i(TAG, "Audio flush")
        audioPlayer.flush()
    }

    override fun onVideoFlush() {
        Log.i(TAG, "Video flush")
        videoDecoder.stop()
    }

    // ---- Helpers ----

    private fun generateHwAddr(): ByteArray {
        val androidId = Settings.Secure.getString(
            contentResolver, Settings.Secure.ANDROID_ID
        ) ?: "aircast-default"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(androidId.toByteArray())
        val addr = ByteArray(6)
        System.arraycopy(digest, 0, addr, 0, 6)
        addr[0] = ((addr[0].toInt() and 0xFE) or 0x02).toByte()
        return addr
    }

    private fun broadcastState() {
        val intent = Intent(Constants.BROADCAST_STATE).apply {
            setPackage(packageName)
            putExtra(Constants.EXTRA_STATE, state.name)
        }
        sendBroadcast(intent)
    }

    // ---- Notification (Phase 3 enhanced) ----

    private fun createNotification(statusText: String, subtitle: String? = null): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AirCastService::class.java).apply {
                action = Constants.ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.btn_stop),
                stopIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Show protocol info in subtitle
        if (subtitle != null) {
            builder.setSubText(subtitle)
        }

        return builder.build()
    }

    private fun updateNotification(statusText: String, subtitle: String? = null) {
        val notification = createNotification(statusText, subtitle)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(Constants.NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
        releaseWakeLock()
        audioPlayer.release()
        dlnaManager.stop()
        registrar.unregister()
        videoDecoder.release()
        nativeBridge.stop()
        nativeBridge.destroy()
        serviceScope.cancel()
    }
}
