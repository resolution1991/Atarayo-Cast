package com.atarayocast.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.atarayocast.app.util.Constants

class AirCastApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        lateinit var instance: AirCastApp
            private set
    }
}
