package com.motobsd.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 单侧盲区告警级别。
 * hi_nibble=left, lo_nibble=right, 值: 0=Safe 1=Warning 2=Critical.
 */
enum class AlertLevel(val value: Int, val label: String) {
    Safe(0, "安全"),
    Warning(1, "Warning"),
    Critical(2, "Critical");

    companion object {
        fun fromValue(v: Int): AlertLevel = entries.firstOrNull { it.value == v } ?: Safe
    }
}

/**
 * 左右两侧盲区告警状态。
 */
data class AlertState(
    val left: AlertLevel = AlertLevel.Safe,
    val right: AlertLevel = AlertLevel.Safe,
) {
    val hasAlert: Boolean get() = left != AlertLevel.Safe || right != AlertLevel.Safe
    val hasCritical: Boolean get() = left == AlertLevel.Critical || right == AlertLevel.Critical
}

/**
 * 全局告警状态持有者 — 供 BleService 写入、OverlayService/UI 读取。
 */
object AlertStateHolder {
    private val _state = MutableStateFlow(AlertState())
    val state: StateFlow<AlertState> = _state.asStateFlow()

    fun update(left: AlertLevel, right: AlertLevel) {
        _state.value = AlertState(left, right)
    }
}
