package com.motobsd.model

/**
 * 单个雷达目标。
 * 对应 target_details 中每目标 4 字节: [range_m: i8, angle_deg: i8, velocity_ms: i8, obj_id: u8].
 * 固件只做原样透传（零裁剪），仅作 Dashboard 展示；告警显示由 alert_status 有无目标驱动。
 */
data class TargetObject(
    /** 距离 (m, 带符号；模块有效探测 0.5-30m) */
    val rangeM: Int = 0,
    /** 角度 (度, 带符号；负=左侧、正=右侧、0=正后方) */
    val angleDeg: Int = 0,
    /** 速度 (m/s, 带符号；正=靠近、负=远离) */
    val velocity: Int = 0,
    /** 雷达跟踪目标 ID (跨帧稳定) */
    val id: Int = 0,
) {
    /** 距离 (m) */
    val rangeMeters: Float get() = rangeM.toFloat()

    /** 侧别: 0=左, 1=右, -1=正后方 (0° 不计侧，与固件 alert_status 规则一致) */
    val side: Int get() = when {
        angleDeg < 0 -> 0
        angleDeg > 0 -> 1
        else -> -1
    }
}
