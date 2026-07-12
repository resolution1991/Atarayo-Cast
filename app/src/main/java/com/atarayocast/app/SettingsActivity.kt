package com.atarayocast.app

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.atarayocast.app.data.AppPrefs
import com.atarayocast.app.databinding.ActivitySettingsBinding
import com.atarayocast.app.service.AirCastService
import com.atarayocast.app.util.Constants
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPrefs
    private var selectedResolutionKey = Constants.Resolution.AUTO.key
    private var serviceRunning = false
    private var adaptiveResolutionEnabled = true
    private var suppressSwitchChanges = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(Constants.EXTRA_STATE)
                ?.let { runCatching { Constants.ConnectionState.valueOf(it) }.getOrNull() }
                ?: return
            applyServiceRunning(state != Constants.ConnectionState.IDLE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        setupEdgeToEdge()
        setupNavigation()
        setupInteractions()
        loadPreferences()
        applyServiceRunning(isServiceRunning())
    }

    private fun setupEdgeToEdge() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsToolbar) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(top = bars.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsScroll) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun setupNavigation() {
        binding.settingsToolbar.navigationContentDescription = getString(R.string.btn_back)
        binding.settingsToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupInteractions() {
        binding.rowDeviceName.setOnClickListener {
            if (!serviceRunning) showDeviceNameDialog()
        }
        binding.rowResolution.setOnClickListener {
            if (!serviceRunning && !binding.swAdaptiveRes.isChecked) showResolutionDialog()
        }

        bindRowToSwitch(binding.rowAdaptiveRes, binding.swAdaptiveRes)
        bindRowToSwitch(binding.rowH265, binding.swH265)
        bindRowToSwitch(binding.rowForceH265, binding.swForceH265)
        bindRowToSwitch(binding.rowKeepScreenOn, binding.swKeepScreenOn)
        bindRowToSwitch(binding.rowFullscreenDefault, binding.swFullscreenDefault)
        bindRowToSwitch(binding.rowPin, binding.swPin)
        bindRowToSwitch(binding.rowBootStart, binding.swBootStart)
        bindRowToSwitch(binding.rowPip, binding.swPip)
        bindRowToSwitch(binding.rowDebug, binding.swDebug)

        binding.swAdaptiveRes.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchChanges) return@setOnCheckedChangeListener
            adaptiveResolutionEnabled = checked
            lifecycleScope.launch { prefs.setAdaptiveResolution(checked) }
            updateRestartRequiredSettings()
        }
        binding.swH265.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchChanges) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setH265Enabled(checked) }
            if (!checked && binding.swForceH265.isChecked) {
                binding.swForceH265.isChecked = false
            }
        }
        binding.swForceH265.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchChanges) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setForceH265Only(checked) }
            if (checked && !binding.swH265.isChecked) binding.swH265.isChecked = true
        }
        binding.swKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitchChanges) lifecycleScope.launch { prefs.setKeepScreenOn(checked) }
        }
        binding.swFullscreenDefault.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitchChanges) lifecycleScope.launch { prefs.setFullscreenDefault(checked) }
        }
        binding.swPin.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchChanges) return@setOnCheckedChangeListener
            if (checked) {
                setSwitchChecked(binding.swPin, false)
                showPinDialog()
            } else {
                lifecycleScope.launch { prefs.setPinEnabled(false) }
            }
        }
        binding.swBootStart.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitchChanges) lifecycleScope.launch { prefs.setBootStart(checked) }
        }
        binding.swPip.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitchChanges) lifecycleScope.launch { prefs.setPipEnabled(checked) }
        }
        binding.swDebug.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitchChanges) lifecycleScope.launch { prefs.setDebugOverlay(checked) }
        }

        binding.btnStopServiceFromSettings.setOnClickListener {
            AirCastService.stop(this)
            applyServiceRunning(false)
            Snackbar.make(
                binding.root,
                R.string.settings_service_stopped_ready,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun bindRowToSwitch(row: View, switch: MaterialSwitch) {
        row.setOnClickListener {
            if (row.isEnabled && switch.isEnabled) switch.toggle()
        }
    }

    private fun loadPreferences() {
        lifecycleScope.launch {
            suppressSwitchChanges = true
            binding.tvDeviceName.text = prefs.deviceName.first()
            selectedResolutionKey = prefs.resolution.first()
            binding.tvResolution.text = Constants.Resolution.fromKey(selectedResolutionKey).displayLabel
            binding.swH265.isChecked = prefs.h265Enabled.first()
            binding.swForceH265.isChecked = prefs.forceH265Only.first()
            adaptiveResolutionEnabled = prefs.adaptiveResolution.first()
            binding.swAdaptiveRes.isChecked = adaptiveResolutionEnabled
            binding.swKeepScreenOn.isChecked = prefs.keepScreenOn.first()
            binding.swFullscreenDefault.isChecked = prefs.fullscreenDefault.first()
            binding.swPin.isChecked = prefs.pinEnabled.first()
            binding.swBootStart.isChecked = prefs.bootStart.first()
            binding.swPip.isChecked = prefs.pipEnabled.first()
            binding.swDebug.isChecked = prefs.debugOverlay.first()
            suppressSwitchChanges = false
            updateRestartRequiredSettings()
        }
    }

    private fun showDeviceNameDialog() {
        val inputLayout = createTextInputLayout(getString(R.string.dialog_device_name_hint))
        val input = TextInputEditText(this).apply {
            setSingleLine(true)
            setText(binding.tvDeviceName.text)
            selectAll()
        }
        inputLayout.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.device_name_label)
            .setView(wrapDialogField(inputLayout))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                    .ifBlank { getString(R.string.device_name_default) }
                binding.tvDeviceName.text = name
                lifecycleScope.launch { prefs.setDeviceName(name) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showResolutionDialog() {
        val resolutions = Constants.Resolution.entries.filter { it != Constants.Resolution.AUTO }
        val items = resolutions.map { it.displayLabel }.toTypedArray()
        val checked = resolutions.indexOfFirst { it.key == selectedResolutionKey }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_resolution)
            .setSingleChoiceItems(items, checked) { dialog, which ->
                val resolution = resolutions[which]
                selectedResolutionKey = resolution.key
                binding.tvResolution.text = resolution.displayLabel
                lifecycleScope.launch { prefs.setResolution(resolution.key) }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPinDialog() {
        val inputLayout = createTextInputLayout(getString(R.string.dialog_pin_hint)).apply {
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setSingleLine(true)
        }
        inputLayout.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_pin_title)
            .setMessage(R.string.dialog_pin_message)
            .setView(wrapDialogField(inputLayout))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text?.toString()?.trim().orEmpty()
                if (pin.length != 4 || pin.any { !it.isDigit() }) {
                    inputLayout.error = getString(R.string.dialog_pin_error)
                    return@setOnClickListener
                }
                inputLayout.error = null
                lifecycleScope.launch {
                    prefs.setPinCode(pin)
                    prefs.setPinEnabled(true)
                }
                setSwitchChecked(binding.swPin, true)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun createTextInputLayout(fieldHint: String): TextInputLayout =
        TextInputLayout(this).apply {
            hint = fieldHint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }

    private fun wrapDialogField(field: View): View {
        return FrameLayout(this).apply {
            val horizontal = dp(24)
            val top = dp(8)
            addView(
                field,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = horizontal
                    rightMargin = horizontal
                    topMargin = top
                }
            )
            setPadding(0, 0, 0, dp(4))
        }
    }

    private fun applyServiceRunning(running: Boolean) {
        serviceRunning = running
        updateRestartRequiredSettings()
    }

    private fun updateRestartRequiredSettings() {
        val canEdit = !serviceRunning
        binding.serviceRunningBanner.visibility = if (serviceRunning) View.VISIBLE else View.GONE

        listOf(
            binding.rowDeviceName,
            binding.rowAdaptiveRes,
            binding.rowH265,
            binding.rowForceH265,
            binding.rowKeepScreenOn,
            binding.rowPin
        ).forEach { setRowEnabled(it, canEdit) }

        binding.swAdaptiveRes.isEnabled = canEdit
        binding.swH265.isEnabled = canEdit
        binding.swForceH265.isEnabled = canEdit
        binding.swKeepScreenOn.isEnabled = canEdit
        binding.swPin.isEnabled = canEdit
        setResolutionRowEnabled(canEdit && !adaptiveResolutionEnabled)
    }

    private fun setResolutionRowEnabled(enabled: Boolean) {
        setRowEnabled(binding.rowResolution, enabled)
        binding.tvResolution.isEnabled = enabled
    }

    private fun setRowEnabled(row: View, enabled: Boolean) {
        row.isEnabled = enabled
        row.isClickable = enabled
        row.alpha = if (enabled) 1f else 0.42f
    }

    private fun setSwitchChecked(switch: MaterialSwitch, checked: Boolean) {
        suppressSwitchChanges = true
        switch.isChecked = checked
        suppressSwitchChanges = false
    }

    private fun isServiceRunning(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return activityManager.getRunningServices(Integer.MAX_VALUE).any {
            it.service.className == AirCastService::class.java.name
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(Constants.BROADCAST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        applyServiceRunning(isServiceRunning())
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: IllegalArgumentException) {
            // The receiver may not have completed registration during a fast transition.
        }
    }
}
