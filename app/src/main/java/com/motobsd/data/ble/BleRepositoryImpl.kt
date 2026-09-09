package com.motobsd.data.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.Context
import com.motobsd.ble.Protocol
import com.motobsd.data.settings.SettingsRepository
import com.motobsd.model.AlertLevel
import com.motobsd.model.BleConnectionState
import com.motobsd.model.DeviceStatus
import com.motobsd.model.TargetObject
import com.motobsd.model.TargetRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
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
    private var rssiJob: Job? = null
    /** 模块是否朝后安装；true=后装（默认），模块右侧=骑手左侧，App 需要镜像角度 */
    @Volatile private var radarFacesRear: Boolean = true
    /** 最近一帧原始目标（模块视角），用于安装朝向变化时重新映射，无需等下一帧 */
    private var rawTargets: List<TargetObject> = emptyList()
    /** DFU 期间抑制自动重连，连接由 Nordic DFU 服务接管 */
    private var dfuInProgress: Boolean = false
    /** 手动发起连接/重连：重试次数少、失败提示明确；骑行中断线自动重连则长时间重试 */
    private var manualConnect: Boolean = false

    // ── 状态 ──────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _alertState = MutableStateFlow(Pair(AlertLevel.Safe, AlertLevel.Safe))
    override val alertState: StateFlow<Pair<AlertLevel, AlertLevel>> = _alertState.asStateFlow()

    /** 左右威胁度 0~1：由 target_details 距离+速度计算，驱动悬浮窗连续亮度/长度/颜色 */
    private val _threatState = MutableStateFlow(Pair(0f, 0f))
    override val threatState: StateFlow<Pair<Float, Float>> = _threatState.asStateFlow()

    /** alert_status 的左右有无目标（已转换为骑手视角；仅作威胁度下限兜底） */
    private var leftPresent = false
    private var rightPresent = false

    private val _deviceStatus = MutableStateFlow(DeviceStatus())
    override val deviceStatus: StateFlow<DeviceStatus> = _deviceStatus.asStateFlow()

    private val _targets = MutableStateFlow<List<TargetObject>>(emptyList())
    override val targets: StateFlow<List<TargetObject>> = _targets.asStateFlow()

    /** 目标事件记录表：obj_id → 记录；仅在主线程回调中访问 */
    private val targetRecordMap = LinkedHashMap<Int, TargetRecord>()
    private val _targetRecords = MutableStateFlow<List<TargetRecord>>(emptyList())
    override val targetRecords: StateFlow<List<TargetRecord>> = _targetRecords.asStateFlow()

    private val _disInfo = MutableStateFlow<Map<UUID, String>>(emptyMap())
    override val disInfo: StateFlow<Map<UUID, String>> = _disInfo.asStateFlow()

    private val _lastMac = MutableStateFlow<String?>(null)
    override val lastMac: StateFlow<String?> = _lastMac.asStateFlow()

    private val _deviceName = MutableStateFlow("")
    override val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    override val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    // Coroutine scope for reconnection and device status observation
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        // Load saved MAC
        scope.launch {
            settings.lastMac.collect { mac ->
                _lastMac.value = mac
            }
        }

        // 安装朝向变化时立即把当前原始目标重新映射到骑手视角
        scope.launch {
            settings.radarFacesRear.collect { rear ->
                radarFacesRear = rear
                remapTargetsToRider()
            }
        }
    }

    // ── 扫描 ──────────────────────────────────────────────

    override fun scan(): Flow<List<ScanResult>> {
        _connectionState.value = BleConnectionState.Scanning
        return scanner.scan().onCompletion {
            // 扫描结束（10s 超时 / 页面取消 / 异常）后复位，避免全局状态卡在"扫描中"
            if (_connectionState.value is BleConnectionState.Scanning) {
                _connectionState.value = BleConnectionState.Disconnected
            }
        }
    }

    // ── 连接 ──────────────────────────────────────────────

    override suspend fun connect(mac: String) {
        dfuInProgress = false
        manualConnect = true
        reconnectJob?.cancel()
        _connectionState.value = BleConnectionState.Connecting(mac)

        val cm = createAndSetupConnectionManager()
        connectionManager?.close()
        connectionManager = cm

        try {
            val adapter =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val device = adapter.getRemoteDevice(mac)
            cm.connectTo(device)
        } catch (e: Exception) {
            // MAC 非法 / 蓝牙不可用等立即可抛的失败：释放刚创建的管理器并回到未连接，
            // 避免状态卡死在 Connecting（连接根本没发起，不会有回调来复位状态）
            cm.close()
            if (connectionManager === cm) connectionManager = null
            _connectionState.value = BleConnectionState.Disconnected
            throw e
        }

        // 保存 MAC
        _lastMac.value = mac
        settings.setLastMac(mac)
    }

    override fun disconnect() {
        manualConnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        rssiJob?.cancel()
        rssiJob = null
        _rssi.value = null
        _connectionState.value = BleConnectionState.Disconnected
        connectionManager?.disconnect()?.enqueue()
        // 清除状态
        _alertState.value = Pair(AlertLevel.Safe, AlertLevel.Safe)
        _threatState.value = Pair(0f, 0f)
        leftPresent = false
        rightPresent = false
        _targets.value = emptyList()
        rawTargets = emptyList()
        targetRecordMap.clear()
        _targetRecords.value = emptyList()
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

    override suspend fun enterDfuMode(): Boolean {
        val cm = connectionManager ?: return false
        if (_lastMac.value == null) return false

        // DFU 期间不自动重连：固件复位进 bootloader 后由 DfuService 接管连接
        dfuInProgress = true
        reconnectJob?.cancel()
        reconnectJob = null

        val sent = suspendCancellableCoroutine { cont ->
            cm.triggerDfu(onDone = { ok -> if (cont.isActive) cont.resume(ok) })
        }
        if (!sent) {
            dfuInProgress = false
            return false
        }

        // 等固件收到 0x01 后复位，断开当前 GATT 连接并释放
        _connectionState.value = BleConnectionState.Disconnected
        delay(1500)
        cm.close()
        connectionManager = null
        return true
    }

    override suspend fun readDeviceName(): String? =
        connectionManager?.readDeviceNameResult()

    override fun writeDeviceName(name: String) {
        connectionManager?.writeDeviceName(name)
    }

    // ── ConnectionManager 创建 ────────────────────────────

    private fun createAndSetupConnectionManager(): BleConnectionManager {
        return BleConnectionManager(context).apply {
            onReady = {
                manualConnect = false
                _connectionState.value = BleConnectionState.Ready
                startRssiPolling()
            }

            onServicesInvalidated = {
                // Nordic BleManager reports services invalidated after gatt.disconnect()
                handleGattDisconnect()
            }

            // 固件上报模块原始视角的左右 presence（0/1）。
            // 这里按安装朝向转换到骑手视角后，仅作为 threat 兜底和告警摘要；
            // 有 → Warning，无 → Safe。
            onAlertChanged = { rawLeft, rawRight ->
                val (l, r) = toRiderPresence(rawLeft, rawRight)
                this@BleRepositoryImpl.leftPresent = l
                this@BleRepositoryImpl.rightPresent = r
                _alertState.value = Pair(
                    if (l) AlertLevel.Warning else AlertLevel.Safe,
                    if (r) AlertLevel.Warning else AlertLevel.Safe,
                )
                applyThreat()
            }

            onTargetDetails = { list ->
                rawTargets = list
                remapTargetsToRider()
            }

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
        // DFU 进行中：固件正在复位进 bootloader，断开属于预期行为
        if (dfuInProgress) return

        val current = _connectionState.value
        when (current) {
            // 用户主动断开 → 已在 Disconnected 状态，不处理
            // Error（手动/自动重连已放弃）：迟到的断开回调来自旧连接的残留事件，
            // 忽略，避免覆盖需要用户干预的明确失败状态
            is BleConnectionState.Disconnected,
            is BleConnectionState.Error,
                -> return

            // 之前已连接或在连接中 → 意外断开，开始重连
            is BleConnectionState.Ready,
            is BleConnectionState.Connecting,
                -> {
                rssiJob?.cancel()
                rssiJob = null
                _rssi.value = null
                // 断线后状态不可信：告警/目标复位，悬浮窗回到安全显示
                _alertState.value = Pair(AlertLevel.Safe, AlertLevel.Safe)
                _threatState.value = Pair(0f, 0f)
                leftPresent = false
                rightPresent = false
                _targets.value = emptyList()
                targetRecordMap.clear()
                _targetRecords.value = emptyList()
                startReconnect(
                    maxAttempts = if (manualConnect) MANUAL_RECONNECT_ATTEMPTS
                    else AUTO_RECONNECT_ATTEMPTS
                )
            }

            // 已在重连中 → 不做额外处理
            is BleConnectionState.Reconnecting -> return

            // 其他状态 → 回 Disconnected
            else -> {
                rssiJob?.cancel()
                rssiJob = null
                _rssi.value = null
                _alertState.value = Pair(AlertLevel.Safe, AlertLevel.Safe)
                _threatState.value = Pair(0f, 0f)
                leftPresent = false
                rightPresent = false
                _targets.value = emptyList()
                targetRecordMap.clear()
                _targetRecords.value = emptyList()
                _connectionState.value = BleConnectionState.Disconnected
            }
        }
    }

    /**
     * 由目标列表计算左右威胁度。
     * 威胁度 = 距离贡献（0m→1，30m→0）+ 接近速度加分；presence 有值但无详情时用下限兜底。
     * 注意：velocity 正=靠近 的语义尚未真机确认，实测后可能需要调整 [THREAT_SPEED_WEIGHT] 或符号。
     */
    private fun applyThreat() {
        val list = _targets.value
        val left = sideThreat(list.filter { it.angleDeg < 0 })
        val right = sideThreat(list.filter { it.angleDeg > 0 })
        _threatState.value = Pair(
            maxOf(left, if (leftPresent) THREAT_PRESENCE_FLOOR else 0f),
            maxOf(right, if (rightPresent) THREAT_PRESENCE_FLOOR else 0f),
        )
    }

    /** 原始目标 → 骑手视角目标；当安装朝向变化时可立即重新映射当前帧 */
    private fun remapTargetsToRider() {
        val rider = rawTargets.map { t ->
            t.copy(angleDeg = Protocol.toRiderAngle(t.angleDeg, radarFacesRear))
        }
        _targets.value = rider
        applyThreat()
        updateTargetRecords(rider)
    }

    /** alert_status 原始 presence → 骑手视角 presence */
    private fun toRiderPresence(rawLeft: Boolean, rawRight: Boolean): Pair<Boolean, Boolean> =
        if (radarFacesRear) Pair(rawRight, rawLeft) else Pair(rawLeft, rawRight)

    private fun sideThreat(targets: List<TargetObject>): Float {
        val nearest = targets.minByOrNull { it.rangeM } ?: return 0f
        var threat = (1f - nearest.rangeM / THREAT_RANGE_MAX).coerceIn(0f, 1f)
        if (nearest.velocity > 0) {
            threat += nearest.velocity * THREAT_SPEED_WEIGHT
        }
        return threat.coerceIn(0f, 1f)
    }

    /**
     * 以 obj_id 为单位维护目标事件记录：
     * - 出现 → 新建；持续存在 → 刷新距离/角度/时间
     * - 本帧未出现 → 标记消失（时间戳=消失时刻），保留 60 秒
     * - 每侧显示上限 4 条在 UI 层截取，这里只做全局容量兜底
     */
    private fun updateTargetRecords(frame: List<TargetObject>) {
        val now = System.currentTimeMillis()
        val seen = HashSet<Int>(frame.size)
        for (t in frame) {
            seen.add(t.id)
            val existing = targetRecordMap[t.id]
            targetRecordMap[t.id] = if (existing == null) {
                TargetRecord(objId = t.id, rangeM = t.rangeM, angleDeg = t.angleDeg, lastSeenAt = now)
            } else {
                existing.copy(rangeM = t.rangeM, angleDeg = t.angleDeg, lastSeenAt = now)
            }
        }

        val expired = ArrayList<Int>()
        for ((id, rec) in targetRecordMap) {
            if (!seen.contains(id) && !rec.disappeared) {
                targetRecordMap[id] = rec.copy(disappeared = true, lastSeenAt = System.currentTimeMillis())
            }
            if (System.currentTimeMillis() - targetRecordMap[id]!!.lastSeenAt > TARGET_RECORD_TTL_MS) {
                expired.add(id)
            }
        }
        for (id in expired) targetRecordMap.remove(id)

        // 容量兜底：极端繁忙时丢弃最老的记录
        if (targetRecordMap.size > MAX_TARGET_RECORDS) {
            val overflow = targetRecordMap.values
                .sortedBy { it.lastSeenAt }
                .take(targetRecordMap.size - MAX_TARGET_RECORDS)
                .map { it.objId }
            for (id in overflow) targetRecordMap.remove(id)
        }

        _targetRecords.value = targetRecordMap.values.sortedByDescending { it.lastSeenAt }
    }

    private fun startReconnect(maxAttempts: Int = AUTO_RECONNECT_ATTEMPTS) {
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

                val mac = _lastMac.value
                if (mac == null) {
                    // 没有可重连的设备：回到未连接，由 UI 引导用户去扫描
                    _connectionState.value = BleConnectionState.Disconnected
                    return@launch
                }

                // 尝试直连。connectTo 是异步的，成败由 onReady/onServicesInvalidated 回调
                // 与下方的超时判断决定，不会抛异常；这里只把"立即可抛异常"也归一为本轮失败，
                // 保证每轮之后都走同一个"达到上限即报错"的出口（修复旧逻辑中 continue
                // 永远跳过 maxAttempts 判断、导致无限重连的问题）。
                val connected = try {
                    val adapter =
                        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    val device = adapter.getRemoteDevice(mac)

                    connectionManager?.close()
                    val cm = createAndSetupConnectionManager()
                    connectionManager = cm
                    cm.connectTo(device)

                    // 等待 15s：onReady 触发后状态不再是 Reconnecting → 成功；
                    // 仍停留在 Reconnecting → 本轮失败
                    delay(15_000L)
                    _connectionState.value !is BleConnectionState.Reconnecting
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 连接异常，视为本轮失败
                    false
                }

                if (connected) return@launch // onReady 已触发，连接成功

                // 本轮失败：达到上限则给出明确失败提示并停止，否则继续下一轮
                if (attempt >= maxAttempts) {
                    manualConnect = false
                    // 释放仍挂着连接请求的 GATT 客户端，避免迟到的断开回调把 Error 覆盖掉
                    connectionManager?.close()
                    connectionManager = null
                    val message = if (maxAttempts <= MANUAL_RECONNECT_ATTEMPTS) {
                        "未找到设备：请确认设备已开机并在附近"
                    } else {
                        "重连失败：已尝试 $attempt 次"
                    }
                    _connectionState.value = BleConnectionState.Error(message)
                    break
                }
            }
        }
    }

    /** 连接就绪后每 5s 读一次 RSSI，供状态页显示信号质量 */
    private fun startRssiPolling() {
        rssiJob?.cancel()
        rssiJob = scope.launch {
            while (isActive && _connectionState.value is BleConnectionState.Ready) {
                val cm = connectionManager
                if (cm != null) {
                    cm.readRssi { rssi -> _rssi.value = rssi }
                }
                delay(RSSI_POLL_INTERVAL_MS)
            }
        }
    }

    companion object {
        /** 目标记录保留时长（消失后） */
        private const val TARGET_RECORD_TTL_MS = 60_000L
        /** 目标记录表容量兜底 */
        private const val MAX_TARGET_RECORDS = 32
        /** 连接 RSSI 轮询间隔 */
        private const val RSSI_POLL_INTERVAL_MS = 5_000L
        /** 威胁度距离标尺：0m→1，30m→0 */
        private const val THREAT_RANGE_MAX = 30f
        /** 接近速度加分权重：10m/s → +0.3（velocity 正=靠近，语义待实测） */
        private const val THREAT_SPEED_WEIGHT = 0.03f
        /** presence 有目标但 target_details 缺失时的威胁度下限 */
        private const val THREAT_PRESENCE_FLOOR = 0.3f
        /** 手动"重连上次设备"最多尝试次数：设备未开机时尽快给出明确失败 */
        private const val MANUAL_RECONNECT_ATTEMPTS = 3
        /** 骑行中意外断线的自动重连次数：可能只是暂时超出范围，给足机会 */
        private const val AUTO_RECONNECT_ATTEMPTS = 10
    }
}
