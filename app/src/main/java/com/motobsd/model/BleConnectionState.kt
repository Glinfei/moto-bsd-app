package com.motobsd.model

/**
 * BLE 连接状态机。
 *
 * 状态转换规则：
 * - 用户主动断开 → [Disconnected]，永不自动重连
 * - BLE 意外断开（范围外/设备掉电）→ [Reconnecting]，指数退避
 * - [Reconnecting] 重试 N 次失败 → [Error]
 * - 用户点扫描 → [Scanning] → 发现设备 → 用户选择 → [Connecting]
 */
sealed class BleConnectionState {
    /** 未连接，用户主动断开后的状态。不会自动重连。 */
    data object Disconnected : BleConnectionState()

    /** 正在扫描 BLE 设备。 */
    data object Scanning : BleConnectionState()

    /** 正在连接指定设备。 */
    data class Connecting(val mac: String) : BleConnectionState()

    /** 已连接，特征值已订阅，数据正常流通。 */
    data object Ready : BleConnectionState()

    /** 意外断开，正在自动重连。 */
    data class Reconnecting(val attempt: Int, val delayMs: Long) : BleConnectionState()

    /** 连接失败，需要用户干预。 */
    data class Error(val message: String) : BleConnectionState()

    /** 用户可读的状态描述。 */
    val label: String
        get() = when (this) {
            is Disconnected -> "未连接"
            is Scanning -> "扫描中"
            is Connecting -> "连接中"
            is Ready -> "已就绪"
            is Reconnecting -> "重连中"
            is Error -> "连接失败"
        }
}
