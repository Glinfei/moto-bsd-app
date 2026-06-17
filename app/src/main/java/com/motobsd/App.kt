package com.motobsd

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 预创建 DFU 通知通道（Nordic DFU 库使用）
        createDfuNotificationChannel()
    }

    private fun createDfuNotificationChannel() {
        val channel = NotificationChannel(
            "dfu",
            getString(R.string.dfu_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.dfu_notification_channel_desc)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
