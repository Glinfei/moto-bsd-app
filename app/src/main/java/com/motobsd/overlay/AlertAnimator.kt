package com.motobsd.overlay

import android.animation.ValueAnimator
import android.graphics.Color
import com.motobsd.model.AlertLevel

/**
 * 告警动画 — 占空比脉冲。
 *
 * | 级别     | 颜色 | 周期  | 亮/灭     | 骑车感知      |
 * |----------|------|-------|-----------|---------------|
 * | Safe     | 灰   | —     | 恒亮      | 安全          |
 * | Warning  | 黄   | 1000ms | 700/300ms | 慢悠悠有车    |
 * | Alert    | 橙   | 500ms  | 300/200ms | 有车在靠近    |
 * | Critical | 红   | 500ms  | 250/250ms | 马上撞 ！！   |
 *
 * 动画实现：一个周期内，前 onMs 毫秒为亮色（渐变到全亮），后 offMs 毫秒为暗色（渐变到暗色）。
 * Critical 周期取 500ms（2Hz）而非 200ms（5Hz）：5Hz 闪烁处于光敏性癫痫诱发频段，
 * 骑行场景应避免。
 *
 * 注意：不做"无新通知自动回 Safe"的兜底——持续告警必须持续显示，
 * 状态复位由 BLE 连接断开时（BleRepositoryImpl）统一处理，与光带模式保持一致。
 */
class AlertAnimator(
    private val onColorUpdate: (Int) -> Unit,
) {
    private var animator: ValueAnimator? = null
    private var currentLevel: AlertLevel = AlertLevel.Safe

    fun setLevel(level: AlertLevel) {
        if (level == currentLevel) return
        currentLevel = level

        if (level == AlertLevel.Safe) {
            animator?.cancel()
            animator = null
            onColorUpdate(COLOR_SAFE)
            return
        }

        animator?.cancel()
        startPulse(level)
    }

    fun release() {
        animator?.cancel()
        animator = null
    }

    /** 断线重连后强制重新启动当前级别的脉冲（即使级别未变化）。 */
    fun restart() {
        if (currentLevel == AlertLevel.Safe) return
        animator?.cancel()
        startPulse(currentLevel)
    }

    // ── Pulse ─────────────────────────────────────────────

    private fun startPulse(level: AlertLevel) {
        val (color, periodMs, onFraction) = when (level) {
            AlertLevel.Warning -> Triple(COLOR_WARNING, 1000, 0.70f)
            AlertLevel.Alert -> Triple(COLOR_ALERT, 500, 0.60f)
            AlertLevel.Critical -> Triple(COLOR_CRITICAL, 500, 0.50f)
            else -> return
        }

        // 暗相位：同色但 alpha=120（压暗但不至于近乎不可见）
        val lowColor = Color.argb(120, Color.red(color), Color.green(color), Color.blue(color))

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = periodMs.toLong()
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val result = if (t < onFraction) {
                    blend(lowColor, color, t / onFraction)
                } else {
                    blend(color, lowColor, (t - onFraction) / (1f - onFraction))
                }
                onColorUpdate(result)
            }
            start()
        }
    }

    // ── Color Utils ───────────────────────────────────────

    companion object {
        val COLOR_SAFE = Color.parseColor("#9E9E9E")
        val COLOR_WARNING = Color.parseColor("#FFC107")
        val COLOR_ALERT = Color.parseColor("#FF9800")
        val COLOR_CRITICAL = Color.parseColor("#F44336")

        private fun blend(c1: Int, c2: Int, ratio: Float): Int {
            val r = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * ratio).toInt()
            val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * ratio).toInt()
            val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * ratio).toInt()
            val a = (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * ratio).toInt()
            return Color.argb(a, r, g, b)
        }
    }
}
