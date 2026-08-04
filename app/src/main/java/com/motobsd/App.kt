package com.motobsd

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.motobsd.service.BleService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        listOf(
            NotificationChannel(
                BleService.CHANNEL_BLE,
                getString(R.string.notification_channel_ble),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.notification_channel_ble_desc) },
            NotificationChannel(
                BleService.CHANNEL_ALERT,
                getString(R.string.notification_channel_alert),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.notification_channel_alert_desc)
                enableVibration(true)
            },
            NotificationChannel(
                "dfu",
                getString(R.string.dfu_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.dfu_notification_channel_desc) },
        ).forEach { nm.createNotificationChannel(it) }
    }
}
