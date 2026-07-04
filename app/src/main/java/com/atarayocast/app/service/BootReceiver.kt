package com.atarayocast.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.atarayocast.app.data.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Boot completed received")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = AppPrefs(context)
                val bootStart = prefs.bootStart.first()
                if (bootStart) {
                    Log.i(TAG, "Auto-starting AirCast service")
                    AirCastService.start(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in boot receiver", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
