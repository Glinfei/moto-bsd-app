package com.motobsd.model

/**
 * 雷达目标事件记录（以 obj_id 为单位，跨帧稳定）。
 *
 * 一个目标只占一条记录：进入时创建，持续存在时刷新距离/角度/时间，
 * 消失后标记 [disappeared] 并保留 60 秒。列表显示上限：每侧 4 条。
 */
data class TargetRecord(
    /** 雷达跟踪目标 ID（跨帧稳定） */
    val objId: Int,
    /** 距离（m） */
    val rangeM: Int,
    /** 角度（度；负=左、正=右、0=正后方） */
    val angleDeg: Int,
    /** 最后出现时间（epoch millis；消失时为消失时刻） */
    val lastSeenAt: Long,
    /** 是否已从当前帧消失（保留 60 秒后清除） */
    val disappeared: Boolean = false,
)
