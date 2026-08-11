package com.motobsd.data.ble

import android.bluetooth.le.ScanResult
import com.motobsd.model.AlertLevel
import com.motobsd.model.BleConnectionState
import com.motobsd.model.DeviceStatus
import com.motobsd.model.TargetObject
import com.motobsd.model.TargetRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * BLE 数据仓库接口。
 *
 * UI 层和 Service 层通过此接口访问 BLE 功能，无需感知底层实现。
 */
interface BleRepository {
    // ── 扫描 ───────────────────────────────────────────

    /** 扫描 BLE 设备，持续发射发现的设备列表。Flow 取消时自动停止扫描。 */
    fun scan(): Flow<List<ScanResult>>

    // ── 连接 ───────────────────────────────────────────

    /** 连接指定 MAC 地址的设备。 */
    suspend fun connect(mac: String)

    /** 用户主动断开连接。进入 [BleConnectionState.Disconnected]，不会自动重连。 */
    fun disconnect()

    /** 当前连接状态。 */
    val connectionState: StateFlow<BleConnectionState>

    // ── 数据流 ─────────────────────────────────────────

    /** 左右盲区告警级别。 */
    val alertState: StateFlow<Pair<AlertLevel, AlertLevel>>

    /** 左右威胁度 0~1（由 target_details 距离 + 接近速度计算，驱动悬浮窗连续显示）。 */
    val threatState: StateFlow<Pair<Float, Float>>

    /** 当前连接 RSSI（dBm，连接就绪后每 5s 轮询一次；未连接为 null）。 */
    val rssi: StateFlow<Int?>

    /** 设备状态（电量/温度/flags）。 */
    val deviceStatus: StateFlow<DeviceStatus>

    /** 雷达目标列表。 */
    val targets: StateFlow<List<TargetObject>>

    /** 目标事件记录（以 obj_id 为单位，消失后保留 60 秒）。 */
    val targetRecords: StateFlow<List<TargetRecord>>

    /** DIS 设备信息（制造商/型号/序列号等）。 */
    val disInfo: StateFlow<Map<UUID, String>>

    /** 上次成功连接的 MAC 地址。 */
    val lastMac: StateFlow<String?>

    // ── 操作 ───────────────────────────────────────────

    /** 控制雷达电源。 */
    fun setRadarPower(on: Boolean)

    /** 系统复位。 */
    fun systemReset()

    /** 触发 DFU 模式。 */
    fun triggerDfu(mode: Int = 0x01)

    /**
     * 进入 DFU 模式：写 dfu_trigger(0x01) 让固件复位进 bootloader，
     * 并在 DFU 期间抑制自动重连（连接由 Nordic DFU 服务接管）。
     * @return 是否成功发出触发指令
     */
    suspend fun enterDfuMode(): Boolean

    /** 读取设备名称并挂起直到完成（成功返回名称，失败返回 null）；结果同步到 [deviceName] */
    suspend fun readDeviceName(): String?

    /** 写入设备名称（UTF-8，最长 20 字节） */
    fun writeDeviceName(name: String)

    /** 设备名称（从 BLE 读取或写入后的回读结果） */
    val deviceName: StateFlow<String>
}
