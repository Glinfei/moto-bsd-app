package com.motobsd.overlay

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.motobsd.model.AlertLevel

/**
 * 告警动画 — 占空比脉冲。
 *
 * | 级别     | 颜色 | 周期  | 亮/灭     | 骑车感知      |
 * |----------|------|-------|-----------|---------------|
 * | Safe     | 灰   | —     | 恒亮      | 安全          |
 * | Warning  | 黄   | 1000ms | 700/300ms | 慢悠悠有车    |
 * | Alert    | 黄   | 500ms  | 300/200ms | 有车在靠近    |
 * | Critical | 红   | 200ms  | 100/100ms | 马上撞 ！！   |
 *
 * 动画实现：一个周期内，前 onMs 毫秒为亮色（渐变到全亮），后 offMs 毫秒为暗色（渐变到暗色）。
 * 安全兜底：1.5s 无新告警自动回 Safe。
 */
class AlertAnimator(
    private val onColorUpdate: (Int) -> Unit,
) {
    private var animator: ValueAnimator? = null
    private var currentLevel: AlertLevel = AlertLevel.Safe
    private var safetyTimer: java.util.Timer? = null

    fun setLevel(level: AlertLevel) {
        if (level == currentLevel) return
        currentLevel = level

        if (level == AlertLevel.Safe) {
            animator?.cancel()
            animator = null
            onColorUpdate(COLOR_SAFE)
            cancelSafetyTimer()
            return
        }

        scheduleSafetyReset()
        animator?.cancel()
        startPulse(level)
    }

    fun release() {
        animator?.cancel()
        animator = null
        cancelSafetyTimer()
    }

    // ── Pulse ─────────────────────────────────────────────

    private fun startPulse(level: AlertLevel) {
        val (color, periodMs, onFraction) = when (level) {
            AlertLevel.Warning -> Triple(COLOR_WARNING, 1000, 0.70f)
            AlertLevel.Alert -> Triple(COLOR_WARNING, 500, 0.60f)
            AlertLevel.Critical -> Triple(COLOR_CRITICAL, 200, 0.50f)
            else -> return
        }

        // 低端：同色但 alpha=40（不压暗，只变淡）
        val lowColor = Color.argb(40, Color.red(color), Color.green(color), Color.blue(color))

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

    // ── Safety ────────────────────────────────────────────

    private fun scheduleSafetyReset() {
        cancelSafetyTimer()
        safetyTimer = java.util.Timer("alert-safety", true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() {
                    Handler(Looper.getMainLooper()).post { setLevel(AlertLevel.Safe) }
                }
            }, 1500)
        }
    }

    private fun cancelSafetyTimer() {
        safetyTimer?.cancel()
        safetyTimer = null
    }

    // ── Color Utils ───────────────────────────────────────

    companion object {
        val COLOR_SAFE = Color.parseColor("#9E9E9E")
        val COLOR_WARNING = Color.parseColor("#FFC107")
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
