package com.atarayocast.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.atarayocast.app.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = Constants.PREFS_NAME)

class AppPrefs(private val context: Context) {

    private val dataStore get() = context.dataStore

    // Device name
    val deviceName: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_NAME] ?: "AirCast-Android"
    }

    suspend fun setDeviceName(name: String) {
        dataStore.edit { it[KEY_DEVICE_NAME] = name }
    }

    // Resolution (stored as key string)
    val resolution: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_RESOLUTION] ?: Constants.Resolution.AUTO.key
    }

    suspend fun setResolution(resolution: String) {
        dataStore.edit { it[KEY_RESOLUTION] = resolution }
    }

    // Adaptive resolution (auto-detect device screen)
    val adaptiveResolution: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ADAPTIVE_RESOLUTION] ?: true
    }

    suspend fun setAdaptiveResolution(enabled: Boolean) {
        dataStore.edit { it[KEY_ADAPTIVE_RESOLUTION] = enabled }
    }

    // H.265 / 4K
    val h265Enabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_H265_ENABLED] ?: false
    }

    suspend fun setH265Enabled(enabled: Boolean) {
        dataStore.edit { it[KEY_H265_ENABLED] = enabled }
    }

    // Keep screen on during casting
    val keepScreenOn: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_KEEP_SCREEN_ON] ?: true
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        dataStore.edit { it[KEY_KEEP_SCREEN_ON] = enabled }
    }

    // PIN auth
    val pinEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_PIN_ENABLED] ?: false
    }

    val pinCode: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_PIN_CODE] ?: ""
    }

    suspend fun setPinEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_PIN_ENABLED] = enabled }
    }

    suspend fun setPinCode(code: String) {
        dataStore.edit { it[KEY_PIN_CODE] = code }
    }

    // Boot start
    val bootStart: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_BOOT_START] ?: false
    }

    suspend fun setBootStart(enabled: Boolean) {
        dataStore.edit { it[KEY_BOOT_START] = enabled }
    }

    // PiP
    val pipEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_PIP_ENABLED] ?: true
    }

    suspend fun setPipEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_PIP_ENABLED] = enabled }
    }

    // Debug overlay
    val debugOverlay: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_DEBUG_OVERLAY] ?: false
    }

    suspend fun setDebugOverlay(enabled: Boolean) {
        dataStore.edit { it[KEY_DEBUG_OVERLAY] = enabled }
    }

    // Fullscreen by default
    val fullscreenDefault: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_FULLSCREEN_DEFAULT] ?: false
    }

    suspend fun setFullscreenDefault(enabled: Boolean) {
        dataStore.edit { it[KEY_FULLSCREEN_DEFAULT] = enabled }
    }

    companion object {
        private val KEY_DEVICE_NAME = stringPreferencesKey(Constants.KEY_DEVICE_NAME)
        private val KEY_RESOLUTION = stringPreferencesKey(Constants.KEY_RESOLUTION)
        private val KEY_H265_ENABLED = booleanPreferencesKey(Constants.KEY_H265_ENABLED)
        private val KEY_PIN_ENABLED = booleanPreferencesKey(Constants.KEY_PIN_ENABLED)
        private val KEY_PIN_CODE = stringPreferencesKey(Constants.KEY_PIN_CODE)
        private val KEY_BOOT_START = booleanPreferencesKey(Constants.KEY_BOOT_START)
        private val KEY_PIP_ENABLED = booleanPreferencesKey(Constants.KEY_PIP_ENABLED)
        private val KEY_DEBUG_OVERLAY = booleanPreferencesKey(Constants.KEY_DEBUG_OVERLAY)
        private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey(Constants.KEY_KEEP_SCREEN_ON)
        private val KEY_ADAPTIVE_RESOLUTION = booleanPreferencesKey(Constants.KEY_ADAPTIVE_RESOLUTION)
        private val KEY_FULLSCREEN_DEFAULT = booleanPreferencesKey(Constants.KEY_FULLSCREEN_DEFAULT)
    }
}
