package com.motobsd.overlay

import android.animation.ValueAnimator
import android.animation.ArgbEvaluator
import android.graphics.Color
import com.motobsd.model.AlertLevel

/**
 * 告警动画：颜色渐变 + 闪烁脉冲。
 */
class AlertAnimator(
    private val onColorUpdate: (Int) -> Unit,
) {
    private var colorAnimator: ValueAnimator? = null
    private var blinkAnimator: ValueAnimator? = null
    private var currentLevel: AlertLevel = AlertLevel.Safe
    private var baseColor: Int = COLOR_SAFE

    fun setLevel(level: AlertLevel) {
        if (level == currentLevel) return
        val from = currentLevel
        currentLevel = level

        val targetColor = colorForLevel(level)
        when {
            level == AlertLevel.Critical -> startBlink(targetColor)
            else -> smoothTransition(fromColor = baseColor, toColor = targetColor)
        }
        baseColor = targetColor
    }

    fun release() {
        colorAnimator?.cancel()
        blinkAnimator?.cancel()
    }

    // ── internal ──────────────────────────────────────────

    private fun smoothTransition(fromColor: Int, toColor: Int) {
        blinkAnimator?.cancel()
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor).apply {
            duration = 300
            addUpdateListener { onColorUpdate(it.animatedValue as Int) }
            start()
        }
    }

    private fun startBlink(targetColor: Int) {
        colorAnimator?.cancel()
        blinkAnimator?.cancel()
        val grayed = Color.argb(
            Color.alpha(targetColor),
            (Color.red(targetColor) * 0.4f).toInt(),
            (Color.green(targetColor) * 0.4f).toInt(),
            (Color.blue(targetColor) * 0.4f).toInt(),
        )
        blinkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                onColorUpdate(blend(grayed, targetColor, fraction))
            }
            start()
        }
    }

    companion object {
        val COLOR_SAFE    = Color.parseColor("#9E9E9E")
        val COLOR_WARNING = Color.parseColor("#FFC107")
        val COLOR_CRITICAL = Color.parseColor("#F44336")

        fun colorForLevel(level: AlertLevel): Int = when (level) {
            AlertLevel.Safe     -> COLOR_SAFE
            AlertLevel.Warning  -> COLOR_WARNING
            AlertLevel.Critical -> COLOR_CRITICAL
        }

        private fun blend(c1: Int, c2: Int, ratio: Float): Int {
            val r = (Color.red(c1)   + (Color.red(c2)   - Color.red(c1))   * ratio).toInt()
            val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * ratio).toInt()
            val b = (Color.blue(c1)  + (Color.blue(c2)  - Color.blue(c1))  * ratio).toInt()
            val a = (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * ratio).toInt()
            return Color.argb(a, r, g, b)
        }
    }
}
