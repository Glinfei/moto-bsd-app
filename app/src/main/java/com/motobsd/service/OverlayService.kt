package com.motobsd.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import com.motobsd.model.OverlayConfig
import com.motobsd.model.OverlaySize
import com.motobsd.model.OverlayStyle
import com.motobsd.overlay.OverlayWindow
import com.motobsd.overlay.OverlayWindowHolder

/**
 * 悬浮窗生命周期管理器。
 *
 * 职责仅限创建/销毁 OverlayWindow 及处理配置变更。
 * 告警数据通过 [OverlayWindowHolder] 内存直通，不经过 Intent。
 */
class OverlayService : Service() {

    private lateinit var overlayWindow: OverlayWindow

    override fun onCreate() {
        super.onCreate()
        overlayWindow = OverlayWindow(this)
        OverlayWindowHolder.window = overlayWindow
        overlayWindow.show(loadConfig())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_CONFIG -> overlayWindow.applyConfig(loadConfig())
            ACTION_STOP -> { overlayWindow.hide(); stopSelf() }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayWindow.onConfigurationChanged(newConfig)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        OverlayWindowHolder.window = null
        overlayWindow.hide()
        super.onDestroy()
    }

    private fun loadConfig(): OverlayConfig {
        val prefs = getSharedPreferences("motobsd", Context.MODE_PRIVATE)
        return OverlayConfig(
            style = OverlayStyle.entries.getOrElse(prefs.getInt("overlay_style", 0)) { OverlayStyle.Dot },
            size = OverlaySize.entries.getOrElse(prefs.getInt("overlay_size", OverlaySize.Large.ordinal)) { OverlaySize.Large },
            alpha = prefs.getInt("overlay_alpha", 60),
            swapLeftRight = prefs.getBoolean("overlay_swap", false),
        )
    }

    companion object {
        const val ACTION_UPDATE_CONFIG = "com.motobsd.action.UPDATE_CONFIG"
        const val ACTION_STOP = "com.motobsd.action.STOP_OVERLAY"

        fun updateConfig(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).apply { action = ACTION_UPDATE_CONFIG })
        }

        fun start(context: Context) {
            context.startService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).apply { action = ACTION_STOP })
        }
    }
}
