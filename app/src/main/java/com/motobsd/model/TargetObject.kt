package com.motobsd.model

/**
 * 单个雷达目标。
 * 对应 target_details 中每帧 6 字节: [range, angle_lo, angle_hi, vel, id, level_side].
 */
data class TargetObject(
    /** 距离 (dm, 0-25.5m) */
    val rangeDm: Int = 0,
    /** 角度 (0.1° steps, 带符号) */
    val angle: Int = 0,
    /** 速度 (m/s, 带符号) */
    val velocity: Int = 0,
    /** 目标 ID (0-255) */
    val id: Int = 0,
    /** hi_nibble=threat_level, lo_nibble=side */
    val levelAndSide: Int = 0,
) {
    /** 距离 (m) */
    val rangeMeters: Float get() = rangeDm / 10f
    /** 威胁级别 0=low 1=mid 2=high */
    val threatLevel: Int get() = (levelAndSide shr 4) and 0x0F
    /** 0=left, 1=right */
    val side: Int get() = levelAndSide and 0x0F
}
