package com.atarayocast.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.atarayocast.app.data.AppPrefs
import com.atarayocast.app.util.Constants
import com.atarayocast.app.service.AirCastService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings activity for managing all AirCast preferences.
 *
 * Covers: device name, resolution, H.265, forced H.265, PIN code, password,
 * keep screen on, boot start, PiP, fullscreen default, debug overlay.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
    }

    private lateinit var prefs: AppPrefs

    // UI references
    private lateinit var tvServiceRunningHint: TextView
    private lateinit var rowDeviceName: View
    private lateinit var rowResolution: View
    private lateinit var rowAdaptiveRes: View
    private lateinit var rowH265: View
    private lateinit var rowForceH265: View
    private lateinit var rowKeepScreenOn: View
    private lateinit var rowFullscreenDefault: View
    private lateinit var rowPin: View
    private lateinit var tvDeviceName: TextView
    private lateinit var tvResolution: TextView
    private lateinit var swH265: MaterialSwitch
    private lateinit var swForceH265: MaterialSwitch
    private lateinit var swAdaptiveRes: MaterialSwitch
    private lateinit var swKeepScreenOn: MaterialSwitch
    private lateinit var swFullscreenDefault: MaterialSwitch
    private lateinit var swPin: MaterialSwitch
    private lateinit var swBootStart: MaterialSwitch
    private lateinit var swPip: MaterialSwitch
    private lateinit var swDebug: MaterialSwitch
    private var selectedResolutionKey: String = Constants.Resolution.AUTO.key
    private var serviceRunning = false
    private var adaptiveResolutionEnabled = true

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val stateName = intent.getStringExtra(Constants.EXTRA_STATE) ?: return
            val state = runCatching { Constants.ConnectionState.valueOf(stateName) }.getOrNull()
            applyServiceRunning(state != null && state != Constants.ConnectionState.IDLE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.apply {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
        }

        prefs = AppPrefs(this)

        // Bind views
        tvServiceRunningHint = findViewById(R.id.tvServiceRunningHint)
        rowDeviceName = findViewById(R.id.rowDeviceName)
        rowResolution = findViewById(R.id.rowResolution)
        rowAdaptiveRes = findViewById(R.id.rowAdaptiveRes)
        rowH265 = findViewById(R.id.rowH265)
        rowForceH265 = findViewById(R.id.rowForceH265)
        rowKeepScreenOn = findViewById(R.id.rowKeepScreenOn)
        rowFullscreenDefault = findViewById(R.id.rowFullscreenDefault)
        rowPin = findViewById(R.id.rowPin)
        tvDeviceName = findViewById(R.id.tvDeviceName)
        tvResolution = findViewById(R.id.tvResolution)
        swH265 = findViewById(R.id.swH265)
        swForceH265 = findViewById(R.id.swForceH265)
        swAdaptiveRes = findViewById(R.id.swAdaptiveRes)
        swKeepScreenOn = findViewById(R.id.swKeepScreenOn)
        swFullscreenDefault = findViewById(R.id.swFullscreenDefault)
        swPin = findViewById(R.id.swPin)
        swBootStart = findViewById(R.id.swBootStart)
        swPip = findViewById(R.id.swPip)
        swDebug = findViewById(R.id.swDebug)

        // Load current values
        lifecycleScope.launch {
            tvDeviceName.text = prefs.deviceName.first()
            selectedResolutionKey = prefs.resolution.first()
            val res = Constants.Resolution.fromKey(selectedResolutionKey)
            tvResolution.text = res.displayLabel
            swH265.isChecked = prefs.h265Enabled.first()
            swForceH265.isChecked = prefs.forceH265Only.first()
            adaptiveResolutionEnabled = prefs.adaptiveResolution.first()
            swAdaptiveRes.isChecked = adaptiveResolutionEnabled
            swKeepScreenOn.isChecked = prefs.keepScreenOn.first()
            swFullscreenDefault.isChecked = prefs.fullscreenDefault.first()
            swPin.isChecked = prefs.pinEnabled.first()
            swBootStart.isChecked = prefs.bootStart.first()
            swPip.isChecked = prefs.pipEnabled.first()
            swDebug.isChecked = prefs.debugOverlay.first()
            updateRestartRequiredSettings()
        }

        // ---- Click listeners ----

        rowDeviceName.setOnClickListener {
            if (!serviceRunning) showDeviceNameDialog()
        }

        rowResolution.setOnClickListener {
            if (!serviceRunning && !swAdaptiveRes.isChecked) {
                showResolutionDialog()
            }
        }

        swAdaptiveRes.setOnCheckedChangeListener { _, checked ->
            adaptiveResolutionEnabled = checked
            lifecycleScope.launch { prefs.setAdaptiveResolution(checked) }
            updateRestartRequiredSettings()
        }

        swH265.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { prefs.setH265Enabled(checked) }
            if (!checked && swForceH265.isChecked) {
                swForceH265.isChecked = false
            }
        }

        swForceH265.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { prefs.setForceH265Only(checked) }
            if (checked && !swH265.isChecked) {
                swH265.isChecked = true
            }
        }

        swKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { prefs.setKeepScreenOn(checked) }
        }

        swFullscreenDefault.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { prefs.setFullscreenDefault(checked) }
        }

        swPin.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch {
                prefs.setPinEnabled(checked)
                if (checked) showPinDialog()
            }
        }

        swBootStart.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { prefs.setBootStart(checked) }
        }

        swPip.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { prefs.setPipEnabled(checked) }
        }

        swDebug.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { prefs.setDebugOverlay(checked) }
        }

        applyServiceRunning(isServiceRunning())
    }

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
        } catch (e: IllegalArgumentException) {
            // Receiver may not have been registered if the Activity was paused during setup.
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---- Dialogs ----

    private fun showDeviceNameDialog() {
        val input = android.widget.EditText(this)
        input.setText(tvDeviceName.text)
        input.setSingleLine()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.device_name_label))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "AirCast-Android" }
                tvDeviceName.text = name
                lifecycleScope.launch { prefs.setDeviceName(name) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showResolutionDialog() {
        val resolutions = Constants.Resolution.entries.filter { it != Constants.Resolution.AUTO }
        val items = resolutions.map { it.displayLabel }.toTypedArray()
        val current = Constants.Resolution.fromKey(selectedResolutionKey)
        val checked = resolutions.indexOfFirst { it.key == current.key }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_resolution))
            .setSingleChoiceItems(items, checked) { dialog, which ->
                val res = resolutions[which]
                selectedResolutionKey = res.key
                tvResolution.text = res.displayLabel
                lifecycleScope.launch { prefs.setResolution(res.key) }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyServiceRunning(running: Boolean) {
        serviceRunning = running
        updateRestartRequiredSettings()
    }

    private fun updateRestartRequiredSettings() {
        val canEditRestartRequired = !serviceRunning
        tvServiceRunningHint.visibility = if (serviceRunning) View.VISIBLE else View.GONE

        listOf(
            rowDeviceName,
            rowAdaptiveRes,
            rowH265,
            rowForceH265,
            rowKeepScreenOn,
            rowFullscreenDefault,
            rowPin
        ).forEach { setRowEnabled(it, canEditRestartRequired) }

        swAdaptiveRes.isEnabled = canEditRestartRequired
        swH265.isEnabled = canEditRestartRequired
        swForceH265.isEnabled = canEditRestartRequired
        swKeepScreenOn.isEnabled = canEditRestartRequired
        swFullscreenDefault.isEnabled = canEditRestartRequired
        swPin.isEnabled = canEditRestartRequired

        setResolutionRowEnabled(canEditRestartRequired && !adaptiveResolutionEnabled)
    }

    private fun setResolutionRowEnabled(enabled: Boolean) {
        setRowEnabled(rowResolution, enabled)
        tvResolution.isEnabled = enabled
    }

    private fun setRowEnabled(row: View, enabled: Boolean) {
        row.isEnabled = enabled
        row.isClickable = enabled
        row.alpha = if (enabled) 1.0f else 0.45f
    }

    private fun isServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(Integer.MAX_VALUE).any {
            it.service.className == AirCastService::class.java.name
        }
    }

    private fun showPinDialog() {
        val input = android.widget.EditText(this)
        input.setHint("0000")
        input.setSingleLine()
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_pin))
            .setMessage("Set 4-digit PIN code")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length == 4 && pin.all { it.isDigit() }) {
                    lifecycleScope.launch { prefs.setPinCode(pin) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
