package com.motobsd.model

/**
 * 单侧盲区告警级别。
 * App 内部显示级别（不再来自固件编码）。
 * 实车数据仅产生 Safe / Warning（固件 alert_status 有无目标：有=Warning、无=Safe）；
 * Alert / Critical 保留用于测试告警模式与后续策略扩展。
 */
enum class AlertLevel(val value: Int, val label: String) {
    Safe(0, "安全"),
    Warning(1, "警告"),
    Alert(2, "警惕"),
    Critical(3, "危险");

    companion object {
        fun fromValue(v: Int): AlertLevel = entries.firstOrNull { it.value == v } ?: Safe
    }
}
