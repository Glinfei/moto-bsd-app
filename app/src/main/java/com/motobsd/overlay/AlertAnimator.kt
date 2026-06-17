package com.motobsd.overlay

import android.animation.ValueAnimator
import android.graphics.Color
import com.motobsd.model.AlertLevel

/**
 * 告警动画：四级响应式脉冲。
 *
 * | 级别     | 颜色   | 周期 | 效果        |
 * |----------|--------|------|-------------|
 * | Safe     | 灰     | —    | 常亮        |
 * | Warning  | 黄     | 800ms| 慢速脉冲    |
 * | Alert    | 黄     | 400ms| 快速脉冲    |
 * | Critical | 红     | 250ms| 红色急闪    |
 */
class AlertAnimator(
    private val onColorUpdate: (Int) -> Unit,
) {
    private var animator: ValueAnimator? = null
    private var currentLevel: AlertLevel = AlertLevel.Safe

    fun setLevel(level: AlertLevel) {
        android.util.Log.d("MotoBSD", "Animator setLevel: ${level.label}, current=${currentLevel.label}")
        if (level == currentLevel) return
        currentLevel = level
        applyLevel()
    }

    fun release() {
        animator?.cancel()
        animator = null
    }

    private fun applyLevel() {
        animator?.cancel()

        when (currentLevel) {
            AlertLevel.Safe -> {
                onColorUpdate(COLOR_SAFE)
            }
            AlertLevel.Warning -> {
                startPulse(COLOR_WARNING, periodMs = 800)
            }
            AlertLevel.Alert -> {
                startPulse(COLOR_WARNING, periodMs = 400)
            }
            AlertLevel.Critical -> {
                startPulse(COLOR_CRITICAL, periodMs = 250)
            }
        }
    }

    private fun startPulse(targetColor: Int, periodMs: Int) {
        val dimmed = dimColor(targetColor, 0.3f)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = periodMs / 2L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                onColorUpdate(blend(dimmed, targetColor, a.animatedValue as Float))
            }
            start()
        }
    }

    companion object {
        val COLOR_SAFE     = Color.parseColor("#9E9E9E")
        val COLOR_WARNING  = Color.parseColor("#FFC107")
        val COLOR_CRITICAL = Color.parseColor("#F44336")

        private fun dimColor(c: Int, factor: Float): Int =
            Color.argb(Color.alpha(c),
                (Color.red(c) * factor).toInt(),
                (Color.green(c) * factor).toInt(),
                (Color.blue(c) * factor).toInt())

        private fun blend(c1: Int, c2: Int, ratio: Float): Int {
            val r = (Color.red(c1)   + (Color.red(c2)   - Color.red(c1))   * ratio).toInt()
            val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * ratio).toInt()
            val b = (Color.blue(c1)  + (Color.blue(c2)  - Color.blue(c1))  * ratio).toInt()
            val a = (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * ratio).toInt()
            return Color.argb(a, r, g, b)
        }
    }
}
