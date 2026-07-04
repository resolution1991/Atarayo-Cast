package com.atarayocast.app.dlna

import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

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

    private val positionRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING) {
                val pos = p.currentPosition.coerceAtLeast(0)
                val dur = p.duration.coerceAtLeast(0)
                onPositionUpdate(pos, dur)
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val mainHandler = android.os.Handler(Looper.getMainLooper())

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    if (player?.playWhenReady == true) {
                        onStateChange(TransportState.PLAYING)
                    }
                }
                Player.STATE_BUFFERING -> {
                    onStateChange(TransportState.TRANSITIONING)
                }
                Player.STATE_ENDED -> {
                    onStateChange(TransportState.STOPPED)
                    stopPositionUpdates()
                }
                Player.STATE_IDLE -> {
                    onStateChange(TransportState.NO_MEDIA_PRESENT)
                    stopPositionUpdates()
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady && player?.playbackState == Player.STATE_READY) {
                onStateChange(TransportState.PLAYING)
            } else if (!playWhenReady && player?.playbackState == Player.STATE_READY) {
                onStateChange(TransportState.PAUSED_PLAYBACK)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error", error)
            onStateChange(TransportState.STOPPED)
            onError(error.message ?: "Playback error")
            stopPositionUpdates()
        }
    }

    fun init() {
        if (player != null) return
        player = ExoPlayer.Builder(appContext).build().also {
            it.addListener(playerListener)
            // Apply pending surface if set before init
            pendingSurface?.let { s -> it.setVideoSurface(s) }
        }
        Log.i(TAG, "DlnaMediaPlayer initialized")
    }

    fun setSurface(surface: Surface?) {
        pendingSurface = surface
        mainHandler.post {
            player?.setVideoSurface(surface)
        }
        Log.i(TAG, "Surface set: ${surface != null}")
    }

    fun setUrl(url: String, title: String? = null) {
        currentUrl = url
        currentTitle = title
        mainHandler.post {
            val p = player ?: return@post
            p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            p.prepare()
            onStateChange(TransportState.TRANSITIONING)
        }
    }

    fun play() {
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
            onStateChange(TransportState.STOPPED)
            stopPositionUpdates()
        }
    }

    fun seekTo(positionMs: Long) {
        mainHandler.post {
            player?.seekTo(positionMs)
        }
    }

    fun getCurrentPosition(): Long {
        return player?.currentPosition?.coerceAtLeast(0) ?: 0
    }

    fun getDuration(): Long {
        return player?.duration?.coerceAtLeast(0) ?: 0
    }

    fun getCurrentUrl(): String? = currentUrl
    fun getCurrentTitle(): String? = currentTitle

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

    fun destroy() {
        stopPositionUpdates()
        player?.release()
        player = null
        Log.i(TAG, "DlnaMediaPlayer destroyed")
    }
}
