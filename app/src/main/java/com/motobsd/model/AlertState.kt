package com.motobsd.model

/**
 * 单侧盲区告警级别。
 * hi_nibble=left, lo_nibble=right, 值: 0=Safe 1=Warning 2=Alert 3=Critical.
 */
enum class AlertLevel(val value: Int, val label: String) {
    Safe(0, "安全"),
    Warning(1, "Warning"),
    Alert(2, "Alert"),
    Critical(3, "Critical");

    companion object {
        fun fromValue(v: Int): AlertLevel = entries.firstOrNull { it.value == v } ?: Safe
    }
}
