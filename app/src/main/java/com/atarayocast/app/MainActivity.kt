package com.atarayocast.app

import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.atarayocast.app.data.AppPrefs
import com.atarayocast.app.databinding.ActivityMainBinding
import com.atarayocast.app.service.AirCastService
import com.atarayocast.app.ui.main.MainViewModel
import com.atarayocast.app.util.Constants
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val NOTIFICATION_PERMISSION_REQUEST = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var serviceBinder: AirCastService.LocalBinder? = null
    private var serviceBound = false

    // Fullscreen / overlay state
    private var isFullscreen = false
    private var overlayVisible = true
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideControlOverlay() }
    private var isCasting = false
    private var pipEnabled = true
    private var debugOverlayEnabled = false

    // Debug overlay updater
    private val debugHandler = Handler(Looper.getMainLooper())
    private val debugRunnable = object : Runnable {
        override fun run() {
            updateDebugOverlay()
            debugHandler.postDelayed(this, 1000)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? AirCastService.LocalBinder
            serviceBinder = binder
            val surface = binding.surfaceView.holder.surface
            if (surface != null && surface.isValid) {
                binder?.setSurface(surface)
            }
            Log.i(TAG, "Bound to AirCastService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null
            serviceBound = false
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val stateName = intent.getStringExtra(Constants.EXTRA_STATE)
            stateName?.let {
                try {
                    val state = Constants.ConnectionState.valueOf(it)
                    viewModel.updateConnectionState(state)
                    updateUI(state)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Unknown state: $stateName")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.initPrefs(AppPrefs(this))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
        }

        setupSurface()
        setupButtons()
        setupTouchListener()
        observeViewModel()

        updateUI(Constants.ConnectionState.IDLE)
    }

    // ---- Surface ----

    private fun setupSurface() {
        binding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "Surface created")
                serviceBinder?.setSurface(holder.surface)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) { }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "Surface destroyed")
                serviceBinder?.setSurface(null)
            }
        })
    }

    // ---- Touch for overlay toggle ----

    private fun setupTouchListener() {
        binding.surfaceView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (isFullscreen) {
                    if (overlayVisible) {
                        hideControlOverlay()
                    } else {
                        showControlOverlay()
                    }
                }
            }
            false
        }
    }

    private fun showControlOverlay() {
        overlayVisible = true
        binding.controlOverlay.animate()
            .alpha(1f)
            .setDuration(200)
            .withStartAction { binding.controlOverlay.visibility = View.VISIBLE }
            .start()
        scheduleOverlayHide()
    }

    private fun hideControlOverlay() {
        if (!isFullscreen) return
        overlayVisible = false
        binding.controlOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { binding.controlOverlay.visibility = View.GONE }
            .start()
    }

    private fun scheduleOverlayHide() {
        hideHandler.removeCallbacks(hideOverlayRunnable)
        if (isFullscreen) {
            hideHandler.postDelayed(hideOverlayRunnable, Constants.CONTROL_AUTO_HIDE_DELAY_MS)
        }
    }

    // ---- Buttons ----

    private fun setupButtons() {
        // Legacy buttons (non-fullscreen card)
        binding.btnStartLegacy.setOnClickListener {
            requestNotificationPermissionAndStart()
        }
        binding.btnStopLegacy.setOnClickListener {
            stopService()
        }

        // Fullscreen toggle
        binding.btnFullscreen.setOnClickListener {
            toggleFullscreen()
            scheduleOverlayHide()
        }

        // Close button — disconnect AirPlay session but keep service running
        binding.btnClose.setOnClickListener {
            Log.i(TAG, "Close button clicked — disconnecting AirPlay, keeping service")
            serviceBinder?.disconnectClient()
            // Exit fullscreen directly — don't wait for broadcast
            isCasting = false
            binding.noInputText.visibility = View.VISIBLE
            if (isFullscreen) {
                isFullscreen = false
                exitFullscreen()
            }
        }

        // Settings
        binding.btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // Refresh button — clears the Surface to remove visual artifacts.
        // Does NOT touch MediaCodec — just paints black on the Surface,
        // and the next decoded frame will overwrite naturally.
        binding.btnRefresh.setOnClickListener {
            Log.i(TAG, "Refresh button clicked — clearing Surface")
            try {
                val holder = binding.surfaceView.holder
                val surface = holder.surface
                if (surface != null && surface.isValid) {
                    val canvas = holder.lockCanvas()
                    if (canvas != null) {
                        canvas.drawColor(android.graphics.Color.BLACK)
                        holder.unlockCanvasAndPost(canvas)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear Surface", e)
            }
            scheduleOverlayHide()
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            enterFullscreen()
        } else {
            exitFullscreen()
        }
    }

    private fun enterFullscreen() {
        // Hide system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Update UI
        binding.btnFullscreen.setImageResource(android.R.drawable.ic_menu_revert)
        binding.btnFullscreen.contentDescription = getString(R.string.btn_exit_fullscreen)

        // Show close button only when casting
        binding.btnClose.visibility = if (isCasting) View.VISIBLE else View.GONE

        // Hide non-fullscreen card
        binding.nonFullscreenCard.visibility = View.GONE

        // Show overlay and schedule hide
        showControlOverlay()
    }

    private fun exitFullscreen() {
        // Show system bars
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())

        // Reset flags
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // Update UI
        binding.btnFullscreen.setImageResource(android.R.drawable.ic_menu_crop)
        binding.btnFullscreen.contentDescription = getString(R.string.btn_fullscreen)
        binding.btnClose.visibility = View.GONE

        // Cancel auto-hide
        hideHandler.removeCallbacks(hideOverlayRunnable)

        // Show overlay permanently
        binding.controlOverlay.animate()
            .alpha(1f)
            .setDuration(200)
            .withStartAction { binding.controlOverlay.visibility = View.VISIBLE }
            .start()
        overlayVisible = true

        // Show non-fullscreen card if not casting
        if (!isCasting) {
            binding.nonFullscreenCard.visibility = View.VISIBLE
        }
    }

    private fun isServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in am.getRunningServices(Integer.MAX_VALUE)) {
            if (service.service.className == AirCastService::class.java.name) {
                return true
            }
        }
        return false
    }

    // ---- Service lifecycle ----

    private fun bindToService() {
        if (serviceBound) return
        val intent = Intent(this, AirCastService::class.java)
        serviceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!serviceBound) {
            Log.w(TAG, "Failed to bind to AirCastService")
        }
    }

    private fun unbindFromService() {
        if (!serviceBound) return
        try {
            serviceBinder?.setSurface(null)
            unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Service not registered")
        }
        serviceBound = false
        serviceBinder = null
    }

    private fun requestNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST
                )
                return
            }
        }
        startService()
    }

    private fun startService() {
        AirCastService.start(this)
        bindToService()
        viewModel.setServiceRunning(true)
        viewModel.updateConnectionState(Constants.ConnectionState.WAITING)
        updateUI(Constants.ConnectionState.WAITING)

        // Auto enter fullscreen if preferred
        lifecycleScope.launch {
            val prefs = AppPrefs(this@MainActivity)
            prefs.fullscreenDefault.collect { defaultFullscreen ->
                if (defaultFullscreen && !isFullscreen) {
                    isFullscreen = true
                    enterFullscreen()
                }
                return@collect
            }
        }

        // Keep screen on if enabled
        updateKeepScreenOn()
        startDebugOverlay()
    }

    private fun stopService() {
        AirCastService.stop(this)
        viewModel.setServiceRunning(false)
        viewModel.updateConnectionState(Constants.ConnectionState.IDLE)
        updateUI(Constants.ConnectionState.IDLE)

        // Exit fullscreen
        if (isFullscreen) {
            isFullscreen = false
            exitFullscreen()
        }

        // Release keep screen on
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopDebugOverlay()
    }

    // ---- Keep screen on ----

    private fun updateKeepScreenOn() {
        lifecycleScope.launch {
            val prefs = AppPrefs(this@MainActivity)
            prefs.keepScreenOn.collect { keepOn ->
                if (keepOn && isCasting) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    // ---- ViewModel ----

    private fun observeViewModel() {
        viewModel.serviceRunning.observe(this) { running ->
            // Update legacy button enabled/disabled states
            binding.btnStartLegacy.isEnabled = !running
            binding.btnStopLegacy.isEnabled = running
            if (running) {
                updateKeepScreenOn()
            }
        }
    }

    // ---- Debug Overlay ----

    private fun startDebugOverlay() {
        if (!debugOverlayEnabled) {
            binding.debugOverlay.visibility = View.GONE
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

    private fun updateDebugOverlay() {
        val state = viewModel.connectionState.value ?: Constants.ConnectionState.IDLE
        val sb = StringBuilder()
        sb.appendLine("AirCast Debug")
        sb.appendLine("State: ${state.name}")
        sb.appendLine("Protocol: ${if (isCasting) "AirPlay" else "-"}")
        sb.appendLine("Service: ${if (viewModel.serviceRunning.value == true) "Running" else "Stopped"}")
        sb.appendLine("Fullscreen: $isFullscreen")
        sb.appendLine("Surface: ${if (binding.surfaceView.holder.surface?.isValid == true) "Valid" else "Invalid"}")

        // Performance metrics from service
        val svc = serviceBinder?.getService()
        if (svc != null && isCasting) {
            sb.appendLine(getString(R.string.debug_fps, svc.debugFps))
            sb.appendLine(getString(R.string.debug_bitrate, svc.debugBitrate))
            sb.appendLine(getString(R.string.debug_codec, svc.debugCodec))
            sb.appendLine(getString(R.string.debug_resolution, svc.debugResW, svc.debugResH))
        }

        binding.debugOverlay.text = sb.toString()
    }

    // ---- UI State ----

    private fun updateUI(state: Constants.ConnectionState) {
        val wasCasting = isCasting
        isCasting = state == Constants.ConnectionState.CONNECTED ||
                   state == Constants.ConnectionState.STREAMING

        // Feature 1: Auto-enter fullscreen when casting starts
        if (isCasting && !wasCasting && !isFullscreen) {
            isFullscreen = true
            enterFullscreen()
        }

        // Auto-exit fullscreen when casting ends (WAITING or IDLE)
        if (!isCasting && wasCasting && isFullscreen) {
            isFullscreen = false
            exitFullscreen()
        }

        when (state) {
            Constants.ConnectionState.IDLE -> {
                binding.statusText.text = getString(R.string.status_idle)
                binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_gray)
                binding.btnClose.visibility = View.GONE
            }
            Constants.ConnectionState.WAITING -> {
                binding.statusText.text = getString(R.string.status_waiting)
                binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_orange)
                binding.btnClose.visibility = View.GONE
            }
            Constants.ConnectionState.CONNECTED -> {
                binding.statusText.text = getString(R.string.status_connected)
                binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_green)
                if (isFullscreen) {
                    binding.btnClose.visibility = View.VISIBLE
                }
            }
            Constants.ConnectionState.STREAMING -> {
                binding.statusText.text = getString(R.string.status_streaming)
                binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_green)
                if (isFullscreen) {
                    binding.btnClose.visibility = View.VISIBLE
                }
            }
        }

        // Feature 4: Show "no input" text when not casting, hide when casting
        binding.noInputText.visibility = if (isCasting) View.GONE else View.VISIBLE

        // Update non-fullscreen card
        if (!isFullscreen) {
            if (isCasting) {
                binding.nonFullscreenCard.visibility = View.GONE
            } else {
                binding.nonFullscreenCard.visibility = View.VISIBLE
                binding.connectionInfo.text = when (state) {
                    Constants.ConnectionState.IDLE -> getString(R.string.status_idle)
                    Constants.ConnectionState.WAITING -> "AirPlay: ${Constants.RAOP_PORT}  |  DLNA: ${Constants.DLNA_HTTP_PORT}"
                    else -> getString(R.string.status_streaming)
                }
            }
        }

        // Update keep screen on
        updateKeepScreenOn()
    }

    // ---- Lifecycle ----

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(Constants.BROADCAST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }

        // Re-bind to running service if returning from another activity
        if (isServiceRunning()) {
            bindToService()
            viewModel.setServiceRunning(true)
        }

        // Load PiP preference
        lifecycleScope.launch {
            pipEnabled = AppPrefs(this@MainActivity).pipEnabled.first()
        }

        // Load debug overlay preference
        lifecycleScope.launch {
            debugOverlayEnabled = AppPrefs(this@MainActivity).debugOverlay.first()
            if (viewModel.serviceRunning.value == true) {
                startDebugOverlay()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Enter PiP mode when user presses home if casting and PiP is enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isCasting && pipEnabled) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
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
            // Hide all controls in PiP mode
            binding.controlOverlay.visibility = View.GONE
            binding.nonFullscreenCard.visibility = View.GONE
        } else {
            // Restore UI when exiting PiP
            if (isFullscreen) {
                showControlOverlay()
            } else {
                binding.controlOverlay.visibility = View.VISIBLE
                if (!isCasting) {
                    binding.nonFullscreenCard.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(stateReceiver) } catch (e: Exception) { /* receiver may not be registered */ }
    }

    override fun onStop() {
        super.onStop()
        unbindFromService()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacks(hideOverlayRunnable)
        debugHandler.removeCallbacks(debugRunnable)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            startService() // Start regardless
        }
    }
}
