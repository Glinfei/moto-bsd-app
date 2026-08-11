package com.motobsd.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.motobsd.data.ble.BleRepository
import com.motobsd.data.overlay.OverlayRepository
import com.motobsd.overlay.OverlayWindow
import com.motobsd.overlay.OverlayWindowHolder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 悬浮窗生命周期管理器。
 *
 * 配置变更通过 [OverlayRepository.configFlow] 内存直通，零延迟。
 */
@AndroidEntryPoint
class OverlayService : Service() {

    @Inject lateinit var overlayRepository: OverlayRepository
    @Inject lateinit var bleRepository: BleRepository

    private lateinit var overlayWindow: OverlayWindow
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowShown = false
    /** 本次启动是否已通过 ACTION_RIDE_MODE 明确设置过，避免 onCreate 的持久化加载覆盖 */
    private var rideModeIntentHandled = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // 前台通知（保证 Service 不被系统杀死，旋转事件可靠送达）
        startForeground(2001, buildNotification(
            if (canShowOverlay()) "盲区指示运行中" else "需要悬浮窗权限：请到系统设置开启后重试"
        ))

        overlayWindow = OverlayWindow(this, overlayRepository)
        OverlayWindowHolder.window = overlayWindow

        // 恢复上次的骑行模式（进程被杀后 START_STICKY 重启场景）
        scope.launch {
            val rideMode = overlayRepository.loadRideModeEnabled()
            if (!rideModeIntentHandled) overlayWindow.setKeepScreenOn(rideMode)
        }

        // 同步当前连接状态（断线时立即显示灰色呼吸，而非普通"安全"）
        OverlayWindowHolder.updateConnectionState(
            bleRepository.connectionState.value is com.motobsd.model.BleConnectionState.Ready
        )

        // 加载初始配置并显示
        if (canShowOverlay()) scope.launch { showWindow() }

        // 实时监听配置变更（来自 UI 的任何修改）
        scope.launch {
            overlayRepository.configFlow.collectLatest { config ->
                overlayWindow.applyConfig(config)
                syncSwap(config)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH -> if (canShowOverlay()) overlayWindow.refresh()
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                overlayWindow.hide()
                windowShown = false
                stopSelf()
            }
            ACTION_RIDE_MODE -> {
                rideModeIntentHandled = true
                overlayWindow.setKeepScreenOn(
                    intent?.getBooleanExtra(EXTRA_RIDE_MODE, false) ?: false
                )
            }
            else -> if (canShowOverlay() && !windowShown) scope.launch { showWindow() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转 / 折叠屏切换后重新读取屏幕尺寸，避免位置和光带错乱
        if (canShowOverlay()) overlayWindow.refresh()
    }

    override fun onDestroy() {
        isRunning = false
        OverlayWindowHolder.window = null
        overlayWindow.hide()
        windowShown = false
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun showWindow() {
        val config = overlayRepository.loadConfig()
        overlayWindow.show(config)
        syncSwap(config)
        windowShown = true
    }

    private fun canShowOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, com.motobsd.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, BleService.CHANNEL_BLE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MotoBSD 浮窗")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build()
    }

    private fun syncSwap(config: com.motobsd.model.OverlayConfig) {
        (bleRepository as? com.motobsd.data.ble.BleRepositoryImpl)
            ?.setSwapLeftRight(config.swapLeftRight)
    }

    companion object {
        const val ACTION_REFRESH = "com.motobsd.action.REFRESH_OVERLAY"
        const val ACTION_STOP = "com.motobsd.action.STOP_OVERLAY"
        const val ACTION_RIDE_MODE = "com.motobsd.action.RIDE_MODE"
        const val EXTRA_RIDE_MODE = "extra_ride_mode"

        /** 当前是否运行中（供设置页开关显示状态） */
        @Volatile
        var isRunning: Boolean = false

        fun start(context: Context) {
            context.startService(Intent(context, OverlayService::class.java))
        }

        fun refresh(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).apply { action = ACTION_REFRESH }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).apply { action = ACTION_STOP }
            )
        }

        /** 骑行模式：悬浮窗保持屏幕常亮 */
        fun setRideMode(context: Context, enabled: Boolean) {
            context.startService(
                Intent(context, OverlayService::class.java).apply {
                    action = ACTION_RIDE_MODE
                    putExtra(EXTRA_RIDE_MODE, enabled)
                }
            )
        }
    }
}
