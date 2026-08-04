package com.motobsd.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
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

    override fun onCreate() {
        super.onCreate()

        // 前台通知（保证 Service 不被系统杀死，旋转事件可靠送达）
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, com.motobsd.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startForeground(2001, NotificationCompat.Builder(this, BleService.CHANNEL_BLE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MotoBSD 浮窗")
            .setContentText("盲区指示运行中")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build())

        overlayWindow = OverlayWindow(this, overlayRepository)
        OverlayWindowHolder.window = overlayWindow

        // 加载初始配置并显示
        scope.launch {
            val config = overlayRepository.loadConfig()
            overlayWindow.show(config)
            syncSwap(config)
        }

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
            ACTION_REFRESH -> overlayWindow.refresh()
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                overlayWindow.hide()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        OverlayWindowHolder.window = null
        overlayWindow.hide()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun syncSwap(config: com.motobsd.model.OverlayConfig) {
        (bleRepository as? com.motobsd.data.ble.BleRepositoryImpl)
            ?.setSwapLeftRight(config.swapLeftRight)
    }

    companion object {
        const val ACTION_REFRESH = "com.motobsd.action.REFRESH_OVERLAY"
        const val ACTION_STOP = "com.motobsd.action.STOP_OVERLAY"

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
    }
}
