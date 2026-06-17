package com.motobsd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
import com.motobsd.ble.MotoBsdBleManager
import com.motobsd.model.AlertLevel
import com.motobsd.model.BleStateHolder
import com.motobsd.model.ConnectionState
import com.motobsd.overlay.OverlayWindowHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 前台 Service：持有 BLE 连接生命周期。
 */
class BleService : LifecycleService() {

    private lateinit var bleManager: MotoBsdBleManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private val lastNotifyTime = mutableMapOf<String, Long>()

    // 当前告警状态（供通知使用）
    private var currentLeft: AlertLevel = AlertLevel.Safe
    private var currentRight: AlertLevel = AlertLevel.Safe

    override fun onCreate() {
        super.onCreate()
        bleManager = MotoBsdBleManager(this)
        Companion.bleManager = bleManager
        createNotificationChannels()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotoBSD:BleService")
        wakeLock.setReferenceCounted(false)

        bleManager.onAlertChanged = { leftVal, rightVal ->
            var l = AlertLevel.entries.getOrElse(leftVal) { AlertLevel.Safe }
            var r = AlertLevel.entries.getOrElse(rightVal) { AlertLevel.Safe }
            if (Companion.swapLeftRight) { val t = l; l = r; r = t }
            currentLeft = l; currentRight = r
            // BleStateHolder 是唯一数据源，Overlay 从它订阅，不再单独设置
            BleStateHolder.updateAlert(l, r)
            handleAlertNotify(l, r)
            refreshNotification()
        }

        // 监听连接状态
        lifecycleScope.launch {
            bleManager.connectionState.collect { state ->
                refreshNotification()
                when (state) {
                    ConnectionState.Ready -> {
                        wasConnected = true
                        reconnectAttempt = 0
                    }
                    ConnectionState.Idle -> tryReconnect()
                    else -> {}
                }
            }
        }

        // Overlay 从 BleStateHolder 订阅告警（与 Dashboard 同源，保证一致）
        lifecycleScope.launch {
            combine(BleStateHolder.alertLeft, BleStateHolder.alertRight) { l, r -> Pair(l, r) }
                .collect { (l, r) -> OverlayWindowHolder.updateAlert(l, r) }
        }

        // 监听设备状态
        lifecycleScope.launch {
            bleManager.deviceStatus.collect { status ->
                OverlayWindowHolder.updateBattery(status.batteryPercent)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                if (!address.isNullOrBlank()) {
                    val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                    val adapter = btManager.adapter
                    val device = adapter.getRemoteDevice(address)
                    bleManager.connectDevice(device)
                }
            }
            ACTION_SCAN -> {
                manuallyDisconnected = false
                val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = btManager.adapter
                bleManager.startScan(adapter)
            }
            ACTION_DISCONNECT -> {
                wasConnected = false
                manuallyDisconnected = true
                bleManager.disconnect().enqueue()
                // 立即清零告警状态
                BleStateHolder.updateAlert(AlertLevel.Safe, AlertLevel.Safe)
                OverlayWindowHolder.updateAlert(AlertLevel.Safe, AlertLevel.Safe)
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        bleManager.disconnect()
        bleManager.close()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    // ── notification ──────────────────────────────────────

    private var currentNotificationState: ConnectionState = ConnectionState.Idle
    private var lastNotifyRefreshMs: Long = 0

    private fun refreshNotification() {
        // 节流：最多每秒刷新一次通知，防止系统限速丢弃
        val now = System.currentTimeMillis()
        if (now - lastNotifyRefreshMs < 1000 && currentNotificationState == ConnectionState.Ready) return
        lastNotifyRefreshMs = now

        val state = bleManager.connectionState.value
        currentNotificationState = state

        val title = when (state) {
            ConnectionState.Ready -> "⚡ 已连接"
            ConnectionState.Scanning -> "⟳ 扫描中"
            ConnectionState.Connecting -> "⟳ 连接中"
            ConnectionState.Subscribing -> "⟳ 同步中"
            ConnectionState.Reconnecting -> "⟳ 重连中"
            ConnectionState.Failed -> "✗ 连接失败"
            ConnectionState.Idle -> "○ 未连接"
        }

        val alertText = if (state == ConnectionState.Ready)
            "左:${currentLeft.label} 右:${currentRight.label}"
        else
            state.label

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

    // ── reconnect ─────────────────────────────────────────

    private var reconnectAttempt = 0
    private var wasConnected = false
    private var manuallyDisconnected = false // 用户主动断开后禁止重连

    private fun tryReconnect() {
        if (!wasConnected || manuallyDisconnected) return
        reconnectAttempt++
        val delayMs = minOf(1000L * (1 shl minOf(reconnectAttempt - 1, 4)), 30_000L)
        lifecycleScope.launch {
            delay(delayMs)
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = btManager.adapter
            if (!bleManager.connectByMac(adapter)) {
                bleManager.startScan(adapter)
            }
        }
    }

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
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        listOf(
            NotificationChannel(CHANNEL_BLE, "MotoBSD 连接", NotificationManager.IMPORTANCE_LOW).apply {
                description = "BLE 连接状态"
            },
            NotificationChannel(CHANNEL_ALERT, "盲区告警", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Critical 告警"
                enableVibration(true)
            },
        ).forEach { nm.createNotificationChannel(it) }
    }

    companion object {
        /** 供 Activity 调用的静态 BleManager 引用。 */
        var bleManager: MotoBsdBleManager? = null
            private set

        /** 左右反转开关。 */
        var swapLeftRight: Boolean = false

        const val ACTION_CONNECT = "com.motobsd.action.CONNECT"
        const val ACTION_DISCONNECT = "com.motobsd.action.DISCONNECT"
        const val ACTION_SCAN = "com.motobsd.action.SCAN"
        const val ACTION_STOP = "com.motobsd.action.STOP"
        const val EXTRA_ADDRESS = "extra_address"
        const val CHANNEL_BLE = "motobsd_ble"
        const val CHANNEL_ALERT = "motobsd_alert"
        const val NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID = 1002

        fun start(context: Context, targetAddress: String? = null) {
            val intent = Intent(context, BleService::class.java).apply {
                action = ACTION_CONNECT
                targetAddress?.let { putExtra(EXTRA_ADDRESS, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun scan(context: Context) {
            val intent = Intent(context, BleService::class.java).apply { action = ACTION_SCAN }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, BleService::class.java).apply { action = ACTION_DISCONNECT }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BleService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
