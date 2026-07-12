package com.atarayocast.app

import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import com.atarayocast.app.data.AppPrefs
import com.atarayocast.app.databinding.ActivityMainBinding
import com.atarayocast.app.service.AirCastService
import com.atarayocast.app.ui.main.MainViewModel
import com.atarayocast.app.util.Constants
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val NOTIFICATION_PERMISSION_REQUEST = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private val viewModel: MainViewModel by viewModels()
    private var serviceBinder: AirCastService.LocalBinder? = null
    private var serviceBound = false
    private var videoSurface: Surface? = null

    private var isFullscreen = false
    private var overlayVisible = false
    private var isCasting = false
    private var activeProtocol: Constants.Protocol? = null
    private var pipEnabled = true
    private var debugOverlayEnabled = false
    private var keepScreenOnEnabled = true
    private var fullscreenDefaultEnabled = false

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideControlOverlay() }
    private val debugHandler = Handler(Looper.getMainLooper())
    private var preferencesJob: Job? = null

    private val debugRunnable = object : Runnable {
        override fun run() {
            updateDebugOverlay()
            debugHandler.postDelayed(this, 1000)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? AirCastService.LocalBinder ?: return
            serviceBinder = binder
            serviceBound = true

            videoSurface
                ?.takeIf { it.isValid }
                ?.let(binder::setSurface)
            binder.setDlnaPlayerView(binding.dlnaPlayerView)

            activeProtocol = binder.getActiveProtocol()
            val currentState = binder.getConnectionState().let { state ->
                if (state == Constants.ConnectionState.IDLE && isServiceRunning()) {
                    Constants.ConnectionState.WAITING
                } else {
                    state
                }
            }
            viewModel.setServiceRunning(true)
            viewModel.updateConnectionState(currentState)
            updateUI(currentState)
            Log.i(TAG, "Bound to AirCastService: state=$currentState protocol=$activeProtocol")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null
            serviceBound = false
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(Constants.EXTRA_STATE)
                ?.let { runCatching { Constants.ConnectionState.valueOf(it) }.getOrNull() }
                ?: return

            activeProtocol = intent.getStringExtra(Constants.EXTRA_PROTOCOL)?.let { protocol ->
                runCatching { Constants.Protocol.valueOf(protocol) }.getOrNull()
            }
            viewModel.setServiceRunning(state != Constants.ConnectionState.IDLE)
            viewModel.updateConnectionState(state)
            updateUI(state)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)
        viewModel.initPrefs(prefs)

        setupEdgeToEdge()
        setupSurface()
        setupButtons()
        setupTouchControls()
        setupBackNavigation()
        observeViewModel()
        startPreferenceObservers()

        val serviceRunning = isServiceRunning()
        viewModel.setServiceRunning(serviceRunning)
        updateUI(
            if (serviceRunning) Constants.ConnectionState.WAITING
            else Constants.ConnectionState.IDLE
        )
    }

    private fun setupEdgeToEdge() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        ViewCompat.setOnApplyWindowInsetsListener(binding.dashboardScroll) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom
            )
            insets
        }

        val mediaMargin = resources.getDimensionPixelSize(R.dimen.media_control_margin)
        ViewCompat.setOnApplyWindowInsetsListener(binding.mediaTopBar) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = mediaMargin + bars.left
                topMargin = mediaMargin + bars.top
                rightMargin = mediaMargin + bars.right
            }
            insets
        }

        val debugMargin = resources.getDimensionPixelSize(R.dimen.debug_overlay_margin)
        val debugTop = resources.getDimensionPixelSize(R.dimen.debug_overlay_top_margin)
        ViewCompat.setOnApplyWindowInsetsListener(binding.debugOverlay) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = debugMargin + bars.left
                topMargin = debugTop + bars.top
            }
            insets
        }
    }

    private fun setupSurface() {
        binding.surfaceView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                videoSurface?.release()
                videoSurface = Surface(surfaceTexture)
                Log.i(TAG, "Texture Surface created: ${width}x$height")
                serviceBinder?.setSurface(videoSurface)
                updateVideoTransform()
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                updateVideoTransform()
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                Log.i(TAG, "Texture Surface destroyed")
                serviceBinder?.setSurface(null)
                videoSurface?.release()
                videoSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }

        if (binding.surfaceView.isAvailable && videoSurface == null) {
            binding.surfaceView.surfaceTexture?.let { surfaceTexture ->
                videoSurface = Surface(surfaceTexture)
                serviceBinder?.setSurface(videoSurface)
            }
        }
    }

    private fun setupTouchControls() {
        binding.surfaceView.setOnClickListener {
            if (!isCasting || activeProtocol == Constants.Protocol.DLNA) return@setOnClickListener
            if (overlayVisible) hideControlOverlay() else showControlOverlay()
        }

        binding.dlnaPlayerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (!isCasting || activeProtocol != Constants.Protocol.DLNA) return@ControllerVisibilityListener
                if (visibility == View.VISIBLE) showControlOverlay() else hideControlOverlay()
            }
        )
    }

    private fun setupButtons() {
        binding.btnServiceAction.setOnClickListener {
            if (viewModel.serviceRunning.value == true) stopService()
            else requestNotificationPermissionAndStart()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnFullscreen.setOnClickListener {
            if (isFullscreen) exitFullscreen() else enterFullscreen()
        }

        binding.btnEndCasting.setOnClickListener {
            Log.i(TAG, "End casting clicked: protocol=$activeProtocol")
            when (activeProtocol) {
                Constants.Protocol.DLNA -> serviceBinder?.terminateDlnaCasting()
                Constants.Protocol.AIRPLAY, null -> serviceBinder?.disconnectClient()
            }
        }

        // MediaCodec owns the video Surface while AirPlay is active. A CPU
        // lockCanvas() "refresh" competes for the same BufferQueue and breaks
        // old Huawei compositors, so the obsolete action remains unavailable.
        binding.btnRefresh.visibility = View.GONE
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isCasting && isFullscreen) {
                    exitFullscreen()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    private fun showControlOverlay() {
        if (!isCasting || isInPictureInPictureMode) return
        overlayVisible = true
        binding.controlOverlay.animate().cancel()
        binding.controlOverlay.alpha = 0f
        binding.controlOverlay.visibility = View.VISIBLE
        binding.controlOverlay.animate().alpha(1f).setDuration(160).start()
        scheduleOverlayHide()
    }

    private fun hideControlOverlay() {
        if (!isCasting || !overlayVisible) return
        hideHandler.removeCallbacks(hideOverlayRunnable)
        overlayVisible = false
        binding.controlOverlay.animate().cancel()
        binding.controlOverlay.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction { binding.controlOverlay.visibility = View.GONE }
            .start()
    }

    private fun scheduleOverlayHide() {
        hideHandler.removeCallbacks(hideOverlayRunnable)
        if (isCasting) {
            hideHandler.postDelayed(hideOverlayRunnable, Constants.CONTROL_AUTO_HIDE_DELAY_MS)
        }
    }

    private fun enterFullscreen() {
        if (!isCasting || isFullscreen) return
        isFullscreen = true
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        binding.btnFullscreen.setImageResource(R.drawable.ic_fullscreen_exit_24)
        binding.btnFullscreen.contentDescription = getString(R.string.btn_exit_fullscreen)
        ViewCompat.requestApplyInsets(binding.root)
        showControlOverlay()
    }

    private fun exitFullscreen() {
        if (!isFullscreen) {
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
            return
        }
        isFullscreen = false
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        binding.btnFullscreen.setImageResource(R.drawable.ic_fullscreen_24)
        binding.btnFullscreen.contentDescription = getString(R.string.btn_fullscreen)
        ViewCompat.requestApplyInsets(binding.root)
        if (isCasting) showControlOverlay()
    }

    private fun isServiceRunning(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return activityManager.getRunningServices(Integer.MAX_VALUE).any {
            it.service.className == AirCastService::class.java.name
        }
    }

    private fun bindToService() {
        if (serviceBound) return
        serviceBound = bindService(
            Intent(this, AirCastService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        if (!serviceBound) Log.w(TAG, "Failed to bind to AirCastService")
    }

    private fun unbindFromService() {
        if (!serviceBound) return
        try {
            serviceBinder?.setSurface(null)
            serviceBinder?.setDlnaPlayerView(null)
            unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Service not registered", e)
        }
        serviceBound = false
        serviceBinder = null
    }

    private fun requestNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
            return
        }
        startService()
    }

    private fun startService() {
        if (viewModel.serviceRunning.value == true) return
        AirCastService.start(this)
        viewModel.setServiceRunning(true)
        viewModel.updateConnectionState(Constants.ConnectionState.WAITING)
        updateUI(Constants.ConnectionState.WAITING)
        bindToService()
        Snackbar.make(binding.root, R.string.service_started_message, Snackbar.LENGTH_SHORT).show()
    }

    private fun stopService() {
        if (viewModel.serviceRunning.value != true) return
        AirCastService.stop(this)
        activeProtocol = null
        viewModel.setServiceRunning(false)
        viewModel.updateConnectionState(Constants.ConnectionState.IDLE)
        updateUI(Constants.ConnectionState.IDLE)
        unbindFromService()
        Snackbar.make(binding.root, R.string.service_stopped_message, Snackbar.LENGTH_SHORT).show()
    }

    private fun startPreferenceObservers() {
        if (preferencesJob != null) return
        preferencesJob = lifecycleScope.launch {
            launch {
                prefs.keepScreenOn.collect { enabled ->
                    keepScreenOnEnabled = enabled
                    updateKeepScreenOn()
                }
            }
            launch {
                prefs.fullscreenDefault.collect { fullscreenDefaultEnabled = it }
            }
            launch {
                prefs.pipEnabled.collect { pipEnabled = it }
            }
            launch {
                prefs.debugOverlay.collect { enabled ->
                    debugOverlayEnabled = enabled
                    refreshDebugOverlay()
                }
            }
        }
    }

    private fun updateKeepScreenOn() {
        if (keepScreenOnEnabled && isCasting) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun observeViewModel() {
        viewModel.serviceRunning.observe(this) {
            renderDashboard()
        }
        viewModel.deviceName.observe(this) { name ->
            binding.deviceNameText.text = name
            binding.mediaSubtitle.text = "$name · ${getString(R.string.media_connected_subtitle)}"
        }
    }

    private fun startDebugOverlay() {
        if (!debugOverlayEnabled || !isCasting || isInPictureInPictureMode) {
            stopDebugOverlay()
            return
        }
        binding.debugOverlay.visibility = View.VISIBLE
        debugHandler.removeCallbacks(debugRunnable)
        debugHandler.post(debugRunnable)
    }

    private fun stopDebugOverlay() {
        debugHandler.removeCallbacks(debugRunnable)
        binding.debugOverlay.visibility = View.GONE
    }

    private fun refreshDebugOverlay() {
        if (debugOverlayEnabled && isCasting) startDebugOverlay() else stopDebugOverlay()
    }

    private fun updateDebugOverlay() {
        val state = viewModel.connectionState.value ?: Constants.ConnectionState.IDLE
        val details = buildString {
            appendLine("Atarayo Cast")
            appendLine("State: ${state.name}")
            appendLine("Protocol: ${activeProtocol?.name ?: "-"}")
            appendLine("Fullscreen: $isFullscreen")
            appendLine("Surface: ${if (videoSurface?.isValid == true) "Valid (TextureView)" else "Invalid"}")
            serviceBinder?.getService()?.takeIf { isCasting }?.let { service ->
                appendLine(getString(R.string.debug_fps, service.debugFps))
                appendLine(getString(R.string.debug_bitrate, service.debugBitrate))
                appendLine(getString(R.string.debug_codec, service.debugCodec))
                appendLine(getString(R.string.debug_resolution, service.debugResW, service.debugResH))
                appendLine("协商: ${service.debugNegotiatedWidth}×${service.debugNegotiatedHeight}@${service.debugNegotiatedFps}")
                val decoder = service.decoderDebugSnapshot()
                appendLine("解码器: ${decoder.codecName} (${decoder.mode})")
                appendLine("解码: 已送 ${decoder.fedFrames} · 已渲染 ${decoder.renderedFrames} · 丢弃 ${decoder.droppedFrames}")
                appendLine("队列: ${decoder.pendingFrames} · 输入槽 ${decoder.availableInputs} · 回调 ${decoder.inputCallbacks}")
                appendLine("解码 Surface: ${if (decoder.surfaceAttached) "已绑定" else "未绑定"} · 已配置 ${decoder.configured}")
                appendLine("同步帧: ${if (decoder.waitingForKeyFrame) "等待 IDR" else "已就绪"} · 最近输入 ${decoder.lastInputBytes} B")
                appendLine(
                    "时序: 投入 ${decoder.millisSinceFeed?.let { "${it}ms 前" } ?: "无"} · " +
                        "渲染 ${decoder.millisSinceRender?.let { "${it}ms 前" } ?: "无"}"
                )
                appendLine("输出: ${decoder.renderPath}")
                if (decoder.lastError != "-") appendLine("解码错误: ${decoder.lastError}")
                updateVideoTransform(service.debugResW, service.debugResH)
            }
        }
        binding.debugOverlay.text = details
    }

    private fun updateUI(state: Constants.ConnectionState) {
        val wasCasting = isCasting
        isCasting = state == Constants.ConnectionState.CONNECTED ||
            state == Constants.ConnectionState.STREAMING

        if (!isCasting) activeProtocol = null

        val isDlnaCasting = isCasting && activeProtocol == Constants.Protocol.DLNA
        binding.dashboardScroll.visibility = if (isCasting) View.GONE else View.VISIBLE
        binding.surfaceView.visibility = if (isDlnaCasting) View.INVISIBLE else View.VISIBLE
        binding.dlnaPlayerView.visibility = if (isDlnaCasting) View.VISIBLE else View.GONE

        if (isCasting) {
            binding.mediaTitle.text = if (isDlnaCasting) {
                getString(R.string.media_dlna_title)
            } else {
                getString(R.string.media_airplay_title)
            }
            binding.btnRefresh.visibility = View.GONE

            if (!wasCasting) {
                if (fullscreenDefaultEnabled) enterFullscreen() else exitFullscreen()
                showControlOverlay()
                if (isDlnaCasting) binding.dlnaPlayerView.showController()
            }
        } else {
            hideHandler.removeCallbacks(hideOverlayRunnable)
            binding.controlOverlay.animate().cancel()
            binding.controlOverlay.visibility = View.GONE
            binding.controlOverlay.alpha = 1f
            overlayVisible = false
            binding.dlnaPlayerView.hideController()
            if (wasCasting || isFullscreen) exitFullscreen()
        }

        renderDashboard()
        updateKeepScreenOn()
        refreshDebugOverlay()
    }

    private fun renderDashboard() {
        val running = viewModel.serviceRunning.value == true
        if (running) {
            binding.statusText.text = getString(R.string.status_waiting)
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_green)
            binding.dashboardTitle.text = getString(R.string.main_waiting_title)
            binding.dashboardDescription.text = getString(R.string.main_waiting_desc)
            binding.btnServiceAction.setText(R.string.btn_stop)
            binding.btnServiceAction.setIconResource(R.drawable.ic_stop_24)
            binding.btnServiceAction.backgroundTintList = colorStateList(R.color.surface_container_high)
            binding.btnServiceAction.setTextColor(ContextCompat.getColor(this, R.color.on_surface))
            binding.btnServiceAction.iconTint = colorStateList(R.color.on_surface)
        } else {
            binding.statusText.text = getString(R.string.status_idle)
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant))
            binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_gray)
            binding.dashboardTitle.text = getString(R.string.main_stopped_title)
            binding.dashboardDescription.text = getString(R.string.main_stopped_desc)
            binding.btnServiceAction.setText(R.string.btn_start)
            binding.btnServiceAction.setIconResource(R.drawable.ic_power_24)
            binding.btnServiceAction.backgroundTintList = colorStateList(R.color.primary)
            binding.btnServiceAction.setTextColor(ContextCompat.getColor(this, R.color.on_primary))
            binding.btnServiceAction.iconTint = colorStateList(R.color.on_primary)
        }
    }

    private fun colorStateList(colorRes: Int): ColorStateList =
        ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(Constants.BROADCAST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }

        if (isServiceRunning()) {
            viewModel.setServiceRunning(true)
            bindToService()
        } else if (!isCasting) {
            viewModel.setServiceRunning(false)
            updateUI(Constants.ConnectionState.IDLE)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isCasting && pipEnabled) {
            try {
                val service = serviceBinder?.getService()
                val width = service?.debugResW?.takeIf { it > 0 } ?: 16
                val height = service?.debugResH?.takeIf { it > 0 } ?: 9
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(width, height))
                        .build()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enter PiP mode", e)
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            binding.controlOverlay.visibility = View.GONE
            binding.dashboardScroll.visibility = View.GONE
            stopDebugOverlay()
        } else if (isCasting) {
            showControlOverlay()
            refreshDebugOverlay()
        } else {
            binding.dashboardScroll.visibility = View.VISIBLE
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: IllegalArgumentException) {
            // The receiver may not have completed registration during a fast lifecycle transition.
        }
    }

    override fun onStop() {
        super.onStop()
        unbindFromService()
    }

    override fun onDestroy() {
        hideHandler.removeCallbacks(hideOverlayRunnable)
        debugHandler.removeCallbacks(debugRunnable)
        preferencesJob?.cancel()
        preferencesJob = null
        serviceBinder?.setSurface(null)
        videoSurface?.release()
        videoSurface = null
        super.onDestroy()
    }

    private fun updateVideoTransform(
        videoWidth: Int = serviceBinder?.getService()?.debugResW ?: 0,
        videoHeight: Int = serviceBinder?.getService()?.debugResH ?: 0
    ) {
        val view = binding.surfaceView
        val viewWidth = view.width
        val viewHeight = view.height
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            view.setTransform(null)
            return
        }

        val fitScale = min(
            viewWidth.toFloat() / videoWidth,
            viewHeight.toFloat() / videoHeight
        )
        val contentWidth = videoWidth * fitScale
        val contentHeight = videoHeight * fitScale
        val matrix = Matrix().apply {
            setScale(
                contentWidth / viewWidth,
                contentHeight / viewHeight,
                viewWidth / 2f,
                viewHeight / 2f
            )
        }
        view.setTransform(matrix)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST) return

        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        startService()
        if (!granted) {
            Snackbar.make(
                binding.root,
                R.string.notification_permission_denied_message,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }
}
