package com.motobsd.data.ble

import android.bluetooth.BluetoothDevice
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.observer.ConnectionObserver
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

    /**
     * 单次连接尝试的结果：true = onReady 已就绪，false = 连接失败。
     * 重连循环等待它（上限 [RECONNECT_ATTEMPT_WAIT_MS]），收到信号即结束本轮，替代原先的"盲等 15 秒"。
     */
    private val connectionOutcome = Channel<Boolean>(Channel.CONFLATED)

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
        // 设备列表路径：用户刚从扫描结果里选中设备，协议栈记录是新的，直接连
        dfuInProgress = false
        manualConnect = true
        reconnectJob?.cancel()
        _connectionState.value = BleConnectionState.Connecting(mac)
        connectResolved(mac, device = null)
    }

    override suspend fun reconnect(mac: String) {
        // 手动「重连上次设备」：先短扫描预热协议栈里的设备记录（地址类型）。
        // 未配对设备的该记录只存在蓝牙进程内存（不落盘），重启蓝牙/手机或记录被淘汰后即失效，
        // 此时裸地址直连会因地址类型不符而超时——扫描一次即可重建。
        dfuInProgress = false
        manualConnect = true
        reconnectJob?.cancel()
        _connectionState.value = BleConnectionState.Connecting(mac)

        // 先释放上一轮的管理器：扫描期间它的迟到回调不该触发自动重连循环
        connectionManager?.close()
        connectionManager = null

        val scanned = findScannedDevice(mac, RECONNECT_SCAN_TIMEOUT_MS)

        // 扫描期间用户点了「取消重连」：放弃本次连接
        if (_connectionState.value is BleConnectionState.Disconnected) return

        _connectionState.value = BleConnectionState.Connecting(mac)
        connectResolved(mac, device = scanned)
    }

    /**
     * 发起一次连接。
     * @param device 扫描到的设备对象（自带正确地址类型）；null 时退回 `getRemoteDevice(mac)`
     */
    private suspend fun connectResolved(mac: String, device: BluetoothDevice?) {
        val cm = createAndSetupConnectionManager()
        connectionManager?.close()
        connectionManager = cm

        try {
            val target = device
                ?: (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                    .adapter.getRemoteDevice(mac)
            cm.connectTo(target)
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

    /**
     * 短扫描并按 MAC 找出目标设备的 [BluetoothDevice]（自带地址类型）。
     * 命中即提前结束扫描；扫描不可用（权限缺失/蓝牙关闭）或未命中返回 null，由调用方退回裸地址直连。
     */
    private suspend fun findScannedDevice(mac: String, timeoutMs: Long): BluetoothDevice? = try {
        withTimeoutOrNull(timeoutMs) {
            scanner.scan(timeoutMs = timeoutMs)
                .mapNotNull { results ->
                    results.firstOrNull { it.device.address.equals(mac, ignoreCase = true) }?.device
                }
                .firstOrNull()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
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
        val cm = BleConnectionManager(context)

        // 连接尝试"从未建立连接就失败"时，库不会回调 onServicesInvalidated，只会通知 ConnectionObserver。
        // 缺这条信号会让状态永远停在 Connecting（用户只能手动取消）——这里接住它。
        // 回调内校验 manager 身份：上一轮被 close() 的实例可能有迟到回调，不能误伤新一轮尝试。
        cm.setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) = Unit
            override fun onDeviceConnected(device: BluetoothDevice) = Unit
            override fun onDeviceReady(device: BluetoothDevice) = Unit
            override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) = Unit

            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                if (connectionManager === cm) handleConnectionFailed(reason)
            }
        })

        return cm.apply {
            onReady = {
                // 身份校验：旧实例的迟到就绪/断开回调不能影响新一轮连接
                if (connectionManager === cm) {
                    manualConnect = false
                    _connectionState.value = BleConnectionState.Ready
                    connectionOutcome.trySend(true)
                    startRssiPolling()
                }
            }

            onServicesInvalidated = {
                // Nordic BleManager reports services invalidated after gatt.disconnect()
                if (connectionManager === cm) handleGattDisconnect()
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
     * 连接尝试失败（尚未建立连接）：库不会回调 onServicesInvalidated，必须在这里接住，
     * 否则状态会永远停在 Connecting（旧实现的"偶发失效、只能手动取消"）。
     * 与 [handleGattDisconnect] 的分工：这里是"从没连上"，那里是"连上后断开"。
     */
    private fun handleConnectionFailed(reason: Int) {
        when (_connectionState.value) {
            // 用户发起（设备列表点击 / 重连按钮）：按手动上限进入重连循环，失败后给出明确报错
            is BleConnectionState.Connecting -> startReconnect(
                maxAttempts = if (manualConnect) MANUAL_RECONNECT_ATTEMPTS
                else AUTO_RECONNECT_ATTEMPTS
            )

            // 循环正在等本轮结果：立即让它进入下一轮，不必盲等到超时
            is BleConnectionState.Reconnecting -> connectionOutcome.trySend(false)

            else -> Unit
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

                // 清掉上一轮残留的结果信号，避免旧结果误判本轮
                while (connectionOutcome.tryReceive().isSuccess) { /* drain */ }

                // 尝试直连。connectTo 是异步的：结果由 onReady（true）或
                // onDeviceFailedToConnect（false）经 [connectionOutcome] 送达，
                // 上限 [RECONNECT_ATTEMPT_WAIT_MS] 兜底；所有失败路径统一走下方"达到上限即报错"出口。
                val connected = try {
                    val adapter =
                        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    val device = adapter.getRemoteDevice(mac)

                    connectionManager?.close()
                    val cm = createAndSetupConnectionManager()
                    connectionManager = cm
                    cm.connectTo(device)

                    withTimeoutOrNull(RECONNECT_ATTEMPT_WAIT_MS) { connectionOutcome.receive() } == true
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
        /** 单轮重连尝试的等待上限；收到就绪/失败信号会提前结束本轮 */
        private const val RECONNECT_ATTEMPT_WAIT_MS = 15_000L
        /** 手动重连前的短扫描时长：命中即提前结束，用于刷新协议栈里的设备记录（地址类型） */
        private const val RECONNECT_SCAN_TIMEOUT_MS = 5_000L
    }
}
