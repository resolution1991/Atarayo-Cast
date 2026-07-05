package com.atarayocast.app.dlna

import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Media player for DLNA content. Wraps ExoPlayer and exposes state
 * for AVTransport to query. Handles video (SurfaceView) and audio-only
 * content pushed from DLNA controllers.
 */
class DlnaMediaPlayer(
    private val appContext: android.content.Context,
    private val onStateChange: (TransportState) -> Unit,
    private val onPositionUpdate: (Long, Long) -> Unit, // currentMs, durationMs
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "DlnaMediaPlayer"
    }

    enum class TransportState {
        STOPPED, PLAYING, PAUSED_PLAYBACK, TRANSITIONING, NO_MEDIA_PRESENT
    }

    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var currentTitle: String? = null
    private var audioManager: AudioManager? = null
    private var pendingSurface: Surface? = null
    private var playerView: PlayerView? = null
    @Volatile private var cachedPositionMs: Long = 0
    @Volatile private var cachedDurationMs: Long = 0
    @Volatile private var cachedTransportState: TransportState = TransportState.NO_MEDIA_PRESENT
    @Volatile private var lastError: String? = null
    @Volatile private var videoWidth: Int = 0
    @Volatile private var videoHeight: Int = 0
    @Volatile private var renderedFirstFrame: Boolean = false

    private val positionRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING) {
                updateCachedPosition(p)
                onPositionUpdate(cachedPositionMs, cachedDurationMs)
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val mainHandler = android.os.Handler(Looper.getMainLooper())

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    updateCachedPosition(player)
                    if (player?.playWhenReady == true) {
                        updateTransportState(TransportState.PLAYING)
                    } else {
                        updateTransportState(TransportState.PAUSED_PLAYBACK)
                    }
                }
                Player.STATE_BUFFERING -> {
                    updateCachedPosition(player)
                    updateTransportState(TransportState.TRANSITIONING)
                }
                Player.STATE_ENDED -> {
                    updateCachedPosition(player)
                    updateTransportState(TransportState.STOPPED)
                    stopPositionUpdates()
                }
                Player.STATE_IDLE -> {
                    updateTransportState(if (currentUrl.isNullOrEmpty()) TransportState.NO_MEDIA_PRESENT else TransportState.STOPPED)
                    stopPositionUpdates()
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady && player?.playbackState == Player.STATE_READY) {
                startPositionUpdates()
                updateTransportState(TransportState.PLAYING)
            } else if (!playWhenReady && player?.playbackState == Player.STATE_READY) {
                updateCachedPosition(player)
                onPositionUpdate(cachedPositionMs, cachedDurationMs)
                updateTransportState(TransportState.PAUSED_PLAYBACK)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            updateCachedPosition(player)
            onPositionUpdate(cachedPositionMs, cachedDurationMs)
            onStateChange(cachedTransportState)
            Log.i(TAG, "Position changed: reason=$reason pos=$cachedPositionMs/${cachedDurationMs}")
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error", error)
            lastError = error.message ?: "Playback error"
            updateTransportState(TransportState.STOPPED)
            onError(lastError ?: "Playback error")
            stopPositionUpdates()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            videoWidth = videoSize.width
            videoHeight = videoSize.height
            Log.i(TAG, "Video size: ${videoSize.width}x${videoSize.height} unappliedRotation=${videoSize.unappliedRotationDegrees} pixelRatio=${videoSize.pixelWidthHeightRatio}")
        }

        override fun onRenderedFirstFrame() {
            renderedFirstFrame = true
            Log.i(TAG, "Rendered first DLNA video frame")
        }
    }

    fun init() {
        if (player != null) return
        player = ExoPlayer.Builder(appContext).build().also {
            it.addListener(playerListener)
            playerView?.player = it
            if (playerView == null) {
                pendingSurface?.let { s -> it.setVideoSurface(s) }
            }
        }
        Log.i(TAG, "DlnaMediaPlayer initialized")
    }

    fun setSurface(surface: Surface?) {
        pendingSurface = surface
        mainHandler.post {
            if (playerView == null) {
                player?.setVideoSurface(surface)
            }
        }
        Log.i(TAG, "Surface set: ${surface != null}")
    }

    fun setPlayerView(view: PlayerView?) {
        mainHandler.post {
            if (playerView === view) return@post
            playerView?.player = null
            playerView = view
            if (view != null) {
                view.useController = true
                view.controllerAutoShow = true
                view.controllerShowTimeoutMs = 5000
                player?.clearVideoSurface()
                view.player = player
                view.showController()
                Log.i(TAG, "PlayerView attached for DLNA video")
            } else {
                player?.let { p ->
                    pendingSurface?.let { p.setVideoSurface(it) }
                }
                Log.i(TAG, "PlayerView detached for DLNA video")
            }
        }
    }

    fun setUrl(url: String, title: String? = null, autoPlay: Boolean = false) {
        currentUrl = url
        currentTitle = title
        lastError = null
        renderedFirstFrame = false
        videoWidth = 0
        videoHeight = 0
        cachedPositionMs = 0
        cachedDurationMs = 0
        mainHandler.post {
            val p = player ?: return@post
            val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(url))
            title?.takeIf { it.isNotBlank() }?.let {
                mediaItemBuilder.setMediaMetadata(
                    MediaMetadata.Builder().setTitle(it).build()
                )
            }
            p.setMediaItem(mediaItemBuilder.build())
            p.playWhenReady = autoPlay
            p.prepare()
            updateTransportState(TransportState.TRANSITIONING)
            if (autoPlay) startPositionUpdates()
        }
    }

    fun play() {
        lastError = null
        mainHandler.post {
            player?.playWhenReady = true
            startPositionUpdates()
        }
    }

    fun pause() {
        mainHandler.post {
            player?.playWhenReady = false
        }
    }

    fun stop() {
        mainHandler.post {
            player?.stop()
            player?.clearMediaItems()
            currentUrl = null
            currentTitle = null
            lastError = null
            cachedPositionMs = 0
            cachedDurationMs = 0
            updateTransportState(TransportState.STOPPED)
            stopPositionUpdates()
        }
    }

    fun seekTo(positionMs: Long) {
        mainHandler.post {
            player?.seekTo(positionMs)
        }
    }

    fun getCurrentPosition(): Long {
        return cachedPositionMs
    }

    fun getDuration(): Long {
        return cachedDurationMs
    }

    fun getCurrentUrl(): String? = currentUrl
    fun getCurrentTitle(): String? = currentTitle
    fun getTransportStatus(): String = if (lastError == null) "OK" else "ERROR_OCCURRED"
    fun getLastError(): String? = lastError
    fun getVideoWidth(): Int = videoWidth
    fun getVideoHeight(): Int = videoHeight
    fun hasRenderedFirstFrame(): Boolean = renderedFirstFrame

    fun getTransportState(): TransportState {
        return cachedTransportState
    }

    fun setVolume(volume: Int) {
        // DLNA Volume is 0-100, ExoPlayer expects 0.0-1.0
        val vol = (volume.coerceIn(0, 100) / 100f)
        mainHandler.post {
            player?.volume = vol
        }
    }

    fun getVolume(): Int {
        return ((player?.volume ?: 0f) * 100).toInt().coerceIn(0, 100)
    }

    fun setMute(mute: Boolean) {
        mainHandler.post {
            player?.volume = if (mute) 0f else 1f
        }
    }

    private fun startPositionUpdates() {
        mainHandler.removeCallbacks(positionRunnable)
        mainHandler.post(positionRunnable)
    }

    private fun stopPositionUpdates() {
        mainHandler.removeCallbacks(positionRunnable)
    }

    private fun updateCachedPosition(p: Player?) {
        if (p == null) return
        cachedPositionMs = p.currentPosition.coerceAtLeast(0)
        val duration = p.duration
        cachedDurationMs = if (duration == C.TIME_UNSET || duration < 0) 0 else duration
    }

    private fun updateTransportState(state: TransportState) {
        cachedTransportState = state
        onStateChange(state)
    }

    fun destroy() {
        stopPositionUpdates()
        playerView?.player = null
        playerView = null
        player?.release()
        player = null
        Log.i(TAG, "DlnaMediaPlayer destroyed")
    }
}
