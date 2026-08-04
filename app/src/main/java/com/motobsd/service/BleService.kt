package com.motobsd.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.motobsd.MainActivity
import com.motobsd.data.ble.BleRepository
import com.motobsd.data.overlay.OverlayRepository
import com.motobsd.model.AlertLevel
import com.motobsd.model.BleConnectionState
import com.motobsd.overlay.OverlayWindowHolder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * BLE 前台服务 — 负责 Foreground 通知、WakeLock 保活、告警通知和震动。
 *
 * BLE 连接逻辑全部委托给 [BleRepository]，本 Service 仅观察状态做 UI 层无关的事情。
 */
@AndroidEntryPoint
class BleService : LifecycleService() {

    @Inject lateinit var bleRepository: BleRepository
    @Inject lateinit var overlayRepository: OverlayRepository
    @Inject lateinit var soundManager: com.motobsd.audio.SoundManager

    private lateinit var wakeLock: PowerManager.WakeLock
    private val lastNotifyTime = mutableMapOf<String, Long>()
    private var isReady = false

    // ── Lifecycle ──────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotoBSD:BleService")
        wakeLock.setReferenceCounted(false)

        // 观察连接状态 → 更新通知 / 自动停止
        lifecycleScope.launch {
            bleRepository.connectionState.collect { state ->
                when (state) {
                    is BleConnectionState.Ready -> isReady = true
                    is BleConnectionState.Disconnected -> {
                        if (isReady) {
                            isReady = false
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                            return@collect
                        }
                    }
                    else -> {}
                }
                refreshNotification()
            }
        }

        // 观察告警状态 → 更新 Overlay + 发告警通知 + 播放声音
        lifecycleScope.launch {
            bleRepository.alertState.collect { (left, right) ->
                OverlayWindowHolder.updateAlert(left, right)
                handleAlertNotify(left, right)
                soundManager.updateAlert(left, right)
            }
        }

        // 加载音频设置
        lifecycleScope.launch {
            soundManager.setVolume(overlayRepository.loadSoundVolume())
            soundManager.setLeftFreq(overlayRepository.loadLeftFreq())
            soundManager.setRightFreq(overlayRepository.loadRightFreq())
        }

        // 观察电量 → 更新 Overlay
        lifecycleScope.launch {
            bleRepository.deviceStatus.collect { status ->
                OverlayWindowHolder.updateBattery(status.batteryPercent)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    // ── Notification ──────────────────────────────────────

    private var lastNotifyRefreshMs: Long = 0

    private fun refreshNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotifyRefreshMs < 1000 && isReady) return
        lastNotifyRefreshMs = now

        val state = bleRepository.connectionState.value

        val title = when (state) {
            is BleConnectionState.Ready -> "⚡ 已连接"
            is BleConnectionState.Scanning -> "⟳ 扫描中"
            is BleConnectionState.Connecting -> "⟳ 连接中"
            is BleConnectionState.Reconnecting -> "⟳ 重连中"
            is BleConnectionState.Error -> "✗ 连接失败"
            is BleConnectionState.Disconnected -> "○ 未连接"
        }

        val alertText = if (state is BleConnectionState.Ready) {
            val (l, r) = bleRepository.alertState.value
            "左:${l.label} 右:${r.label}"
        } else {
            state.label
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_BLE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MotoBSD · $title")
            .setContentText(alertText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // ── Alert ─────────────────────────────────────────────

    private fun handleAlertNotify(left: AlertLevel, right: AlertLevel) {
        val side = when {
            left == AlertLevel.Critical -> "left"
            right == AlertLevel.Critical -> "right"
            else -> return
        }
        val now = System.currentTimeMillis()
        val last = lastNotifyTime[side] ?: 0
        if (now - last > 30_000) {
            sendAlertNotification(side)
            vibrate()
            lastNotifyTime[side] = now
        }
    }

    private fun sendAlertNotification(sideLabel: String) {
        val name = if (sideLabel == "left") "左" else "右"
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠ ${name}盲区告警！")
            .setContentText("有车辆靠近，请注意安全")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(
                VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ── Companion ─────────────────────────────────────────

    companion object {
        const val ACTION_STOP = "com.motobsd.action.STOP"
        const val CHANNEL_BLE = "motobsd_ble"
        const val CHANNEL_ALERT = "motobsd_alert"
        const val NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, BleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BleService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
