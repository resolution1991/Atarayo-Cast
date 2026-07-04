package com.atarayocast.app.ui.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atarayocast.app.data.AppPrefs
import com.atarayocast.app.util.Constants
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private var prefs: AppPrefs? = null

    private val _connectionState = MutableLiveData(Constants.ConnectionState.IDLE)
    val connectionState: LiveData<Constants.ConnectionState> = _connectionState

    private val _deviceName = MutableLiveData("AirCast-Android")
    val deviceName: LiveData<String> = _deviceName

    private val _serviceRunning = MutableLiveData(false)
    val serviceRunning: LiveData<Boolean> = _serviceRunning

    fun initPrefs(appPrefs: AppPrefs) {
        prefs = appPrefs
        viewModelScope.launch {
            appPrefs.deviceName.collectLatest { _deviceName.value = it }
        }
    }

    fun updateConnectionState(state: Constants.ConnectionState) {
        _connectionState.value = state
    }

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    fun updateDeviceName(name: String) {
        viewModelScope.launch {
            prefs?.setDeviceName(name)
        }
    }

    override fun onCleared() {
        super.onCleared()
    }
}
