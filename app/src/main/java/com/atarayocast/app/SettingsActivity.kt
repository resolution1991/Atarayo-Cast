package com.atarayocast.app

import android.os.Bundle
import android.view.MenuItem
import android.widget.CompoundButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.atarayocast.app.data.AppPrefs
import com.atarayocast.app.util.Constants
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings activity for managing all AirCast preferences.
 *
 * Covers: device name, resolution, H.265, PIN code, password,
 * keep screen on, boot start, PiP, fullscreen default, debug overlay.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
    }

    private lateinit var prefs: AppPrefs

    // UI references
    private lateinit var tvDeviceName: TextView
    private lateinit var tvResolution: TextView
    private lateinit var swH265: MaterialSwitch
    private lateinit var swAdaptiveRes: MaterialSwitch
    private lateinit var swKeepScreenOn: MaterialSwitch
    private lateinit var swFullscreenDefault: MaterialSwitch
    private lateinit var swPin: MaterialSwitch
    private lateinit var swBootStart: MaterialSwitch
    private lateinit var swPip: MaterialSwitch
    private lateinit var swDebug: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.apply {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
        }

        prefs = AppPrefs(this)

        // Bind views
        tvDeviceName = findViewById(R.id.tvDeviceName)
        tvResolution = findViewById(R.id.tvResolution)
        swH265 = findViewById(R.id.swH265)
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
            val res = Constants.Resolution.fromKey(prefs.resolution.first())
            tvResolution.text = res.displayLabel
            swH265.isChecked = prefs.h265Enabled.first()
            swAdaptiveRes.isChecked = prefs.adaptiveResolution.first()
            swKeepScreenOn.isChecked = prefs.keepScreenOn.first()
            swFullscreenDefault.isChecked = prefs.fullscreenDefault.first()
            swPin.isChecked = prefs.pinEnabled.first()
            swBootStart.isChecked = prefs.bootStart.first()
            swPip.isChecked = prefs.pipEnabled.first()
            swDebug.isChecked = prefs.debugOverlay.first()
        }

        // ---- Click listeners ----

        findViewById<android.view.View>(R.id.rowDeviceName).setOnClickListener {
            showDeviceNameDialog()
        }

        findViewById<android.view.View>(R.id.rowResolution).setOnClickListener {
            showResolutionDialog()
        }

        swAdaptiveRes.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { prefs.setAdaptiveResolution(checked) }
            findViewById<android.view.View>(R.id.rowResolution).isEnabled = !checked
            tvResolution.isEnabled = !checked
        }

        swH265.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { prefs.setH265Enabled(checked) }
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
        val current = Constants.Resolution.fromKey(tvResolution.text.toString())
        val checked = resolutions.indexOfFirst { it.key == current.key }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_resolution))
            .setSingleChoiceItems(items, checked) { dialog, which ->
                val res = resolutions[which]
                tvResolution.text = res.displayLabel
                lifecycleScope.launch { prefs.setResolution(res.key) }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
