package com.motobsd.data.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.Context
import com.motobsd.data.settings.SettingsRepository
import com.motobsd.model.AlertLevel
import com.motobsd.model.BleConnectionState
import com.motobsd.model.DeviceStatus
import com.motobsd.model.TargetObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE 数据仓库实现 — 连接状态机的唯一持有者。
 *
 * 状态转换规则：
 * ```
 * Disconnected ──connect(mac)──▶ Connecting ──onReady──▶ Ready
 *                                                         │
 *                            用户 disconnect()           │ BLE 意外断开
 *                                 ▼                       ▼
 *                           Disconnected            Reconnecting
 *                                                        │
 *                                                  重试N次失败
 *                                                        ▼
 *                                                      Error
 * ```
 */
@Singleton
class BleRepositoryImpl @Inject constructor(
    private val context: Context,
    private val scanner: BleScanner,
    private val settings: SettingsRepository,
) : BleRepository {

    private var connectionManager: BleConnectionManager? = null
    private var reconnectJob: Job? = null
    private var swapLeftRight: Boolean = false

    // ── 状态 ──────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _alertState = MutableStateFlow(Pair(AlertLevel.Safe, AlertLevel.Safe))
    override val alertState: StateFlow<Pair<AlertLevel, AlertLevel>> = _alertState.asStateFlow()

    private val _deviceStatus = MutableStateFlow(DeviceStatus())
    override val deviceStatus: StateFlow<DeviceStatus> = _deviceStatus.asStateFlow()

    private val _targets = MutableStateFlow<List<TargetObject>>(emptyList())
    override val targets: StateFlow<List<TargetObject>> = _targets.asStateFlow()

    private val _disInfo = MutableStateFlow<Map<UUID, String>>(emptyMap())
    override val disInfo: StateFlow<Map<UUID, String>> = _disInfo.asStateFlow()

    private val _lastMac = MutableStateFlow<String?>(null)
    override val lastMac: StateFlow<String?> = _lastMac.asStateFlow()

    private val _deviceName = MutableStateFlow("")
    override val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    // Coroutine scope for reconnection and device status observation
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        // Load saved MAC
        scope.launch {
            settings.lastMac.collect { mac ->
                _lastMac.value = mac
            }
        }
    }

    // ── 扫描 ──────────────────────────────────────────────

    override fun scan(): Flow<List<ScanResult>> {
        _connectionState.value = BleConnectionState.Scanning
        return scanner.scan()
    }

    // ── 连接 ──────────────────────────────────────────────

    override suspend fun connect(mac: String) {
        reconnectJob?.cancel()
        _connectionState.value = BleConnectionState.Connecting(mac)

        val cm = createAndSetupConnectionManager()
        connectionManager?.close()
        connectionManager = cm

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = adapter.getRemoteDevice(mac)
        cm.connectTo(device)

        // 保存 MAC
        _lastMac.value = mac
        settings.setLastMac(mac)
    }

    override fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _connectionState.value = BleConnectionState.Disconnected
        connectionManager?.disconnect()?.enqueue()
        // 清除状态
        _alertState.value = Pair(AlertLevel.Safe, AlertLevel.Safe)
        _targets.value = emptyList()
        _disInfo.value = emptyMap()
    }

    // ── 操作 ──────────────────────────────────────────────

    override fun setRadarPower(on: Boolean) {
        connectionManager?.writeRadarPower(on)
    }

    override fun systemReset() {
        connectionManager?.writeSystemReset()
    }

    override fun triggerDfu(mode: Int) {
        connectionManager?.triggerDfu(mode)
    }

    override fun readDeviceName() {
        connectionManager?.readDeviceName()
    }

    override fun writeDeviceName(name: String) {
        connectionManager?.writeDeviceName(name)
    }

    /** 设置左右反转（由 OverlayService 或 Settings 同步） */
    fun setSwapLeftRight(swap: Boolean) {
        swapLeftRight = swap
    }

    // ── ConnectionManager 创建 ────────────────────────────

    private fun createAndSetupConnectionManager(): BleConnectionManager {
        return BleConnectionManager(context).apply {
            onReady = {
                _connectionState.value = BleConnectionState.Ready
            }

            onServicesInvalidated = {
                // Nordic BleManager reports services invalidated after gatt.disconnect()
                handleGattDisconnect()
            }

            onAlertChanged = { left, right ->
                var l = left; var r = right
                if (swapLeftRight) { val t = l; l = r; r = t }
                _alertState.value = Pair(l, r)
            }

            onTargetDetails = { list -> _targets.value = list }

            onDeviceStatusChanged = { status ->
                _deviceStatus.value = status
            }

            onDisInfoRead = { uuid, value ->
                val map = _disInfo.value.toMutableMap()
                map[uuid] = value
                _disInfo.value = map
            }

            onDeviceNameRead = { name ->
                _deviceName.value = name
            }
        }
    }

    // ── 断线处理 ──────────────────────────────────────────

    private fun handleGattDisconnect() {
        val current = _connectionState.value
        when (current) {
            // 用户主动断开 → 已在 Disconnected 状态，不处理
            is BleConnectionState.Disconnected -> return

            // 之前已连接或在连接中 → 意外断开，开始重连
            is BleConnectionState.Ready,
            is BleConnectionState.Connecting,
                -> startReconnect()

            // 已在重连中 → 不做额外处理
            is BleConnectionState.Reconnecting -> return

            // 其他状态 → 回 Disconnected
            else -> _connectionState.value = BleConnectionState.Disconnected
        }
    }

    private fun startReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 0
            while (isActive) {
                attempt++
                val delayMs = minOf(1000L * (1 shl minOf(attempt - 1, 4)), 30_000L)
                _connectionState.value = BleConnectionState.Reconnecting(attempt, delayMs)
                delay(delayMs)

                // 检查是否在 delay 期间被用户断开了
                if (_connectionState.value is BleConnectionState.Disconnected) return@launch

                val mac = _lastMac.value ?: break

                // 尝试直连
                try {
                    val adapter =
                        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    val device = adapter.getRemoteDevice(mac)

                    connectionManager?.close()
                    val cm = createAndSetupConnectionManager()
                    connectionManager = cm
                    cm.connectTo(device)

                    // connectTo 是异步的，重连结果由 onReady/onServicesInvalidated 回调处理
                    // 这里等待一段时间，如果没连上就继续下一轮重试
                    delay(15_000L)

                    // 如果 15 秒内 onReady 没触发（还在 Reconnecting），说明本次尝试失败
                    if (_connectionState.value is BleConnectionState.Reconnecting) {
                        continue
                    } else {
                        // onReady 触发了，连接成功
                        return@launch
                    }
                } catch (_: Exception) {
                    // 连接异常，继续下一轮重试
                }

                if (attempt >= 10) {
                    _connectionState.value = BleConnectionState.Error("重连失败：已尝试 $attempt 次")
                    break
                }
            }
        }
    }
}
