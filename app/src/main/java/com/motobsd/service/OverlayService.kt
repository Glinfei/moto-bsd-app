package com.motobsd.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import com.motobsd.model.AlertLevel
import com.motobsd.model.OverlayConfig
import com.motobsd.model.OverlaySize
import com.motobsd.model.OverlayStyle
import com.motobsd.overlay.BsdIndicatorView
import com.motobsd.overlay.OverlayWindow

/**
 * 悬浮窗 Service — 管理 [OverlayWindow] 生命周期。
 * 通过前台 Service 保活悬浮窗。
 */
class OverlayService : Service() {

    private lateinit var overlayWindow: OverlayWindow

    override fun onCreate() {
        super.onCreate()
        overlayWindow = OverlayWindow(this)
        val config = loadConfig()
        overlayWindow.show(config)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_ALERT -> {
                val left  = AlertLevel.entries.getOrElse(intent.getIntExtra(EXTRA_LEFT, 0)) { AlertLevel.Safe }
                val right = AlertLevel.entries.getOrElse(intent.getIntExtra(EXTRA_RIGHT, 0)) { AlertLevel.Safe }
                overlayWindow.setAlertLevel(BsdIndicatorView.Side.Left, left)
                overlayWindow.setAlertLevel(BsdIndicatorView.Side.Right, right)
            }
            ACTION_UPDATE_CONFIG -> {
                overlayWindow.applyConfig(loadConfig())
            }
            ACTION_UPDATE_BATTERY -> {
                val pct = intent?.getIntExtra(EXTRA_BATTERY, 0) ?: 0
                overlayWindow.setBattery(pct)
            }
            ACTION_STOP -> {
                overlayWindow.hide()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayWindow.onConfigurationChanged(newConfig)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlayWindow.hide()
        super.onDestroy()
    }

    private fun loadConfig(): OverlayConfig {
        val prefs = getSharedPreferences("motobsd", Context.MODE_PRIVATE)
        return OverlayConfig(
            style = OverlayStyle.entries.getOrElse(
                prefs.getInt("overlay_style", OverlayStyle.Dot.ordinal)
            ) { OverlayStyle.Dot },
            size = OverlaySize.entries.getOrElse(
                prefs.getInt("overlay_size", OverlaySize.Large.ordinal)
            ) { OverlaySize.Large },
            alpha = prefs.getInt("overlay_alpha", 60),
        )
    }

    companion object {
        const val ACTION_UPDATE_ALERT = "com.motobsd.action.UPDATE_ALERT"
        const val ACTION_UPDATE_CONFIG = "com.motobsd.action.UPDATE_CONFIG"
        const val ACTION_STOP = "com.motobsd.action.STOP_OVERLAY"
        const val EXTRA_LEFT = "extra_left"
        const val EXTRA_RIGHT = "extra_right"
        const val ACTION_UPDATE_BATTERY = "com.motobsd.action.UPDATE_BATTERY"
        const val EXTRA_BATTERY = "extra_battery"

        /** 便捷方法：更新告警状态。 */
        fun updateAlert(context: Context, left: AlertLevel, right: AlertLevel) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_UPDATE_ALERT
                putExtra(EXTRA_LEFT, left.ordinal)
                putExtra(EXTRA_RIGHT, right.ordinal)
            }
            context.startService(intent)
        }

        /** 便捷方法：更新悬浮窗配置并刷新。 */
        fun updateConfig(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_UPDATE_CONFIG
            }
            context.startService(intent)
        }

        /** 便捷方法：更新电池百分比。 */
        fun updateBattery(context: Context, pct: Int) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_UPDATE_BATTERY
                putExtra(EXTRA_BATTERY, pct)
            }
            context.startService(intent)
        }

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
