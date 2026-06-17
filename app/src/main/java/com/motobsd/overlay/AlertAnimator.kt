package com.motobsd.overlay

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.motobsd.model.AlertLevel

/**
 * 告警动画 — 占空比脉冲。
 *
 * | 级别     | 颜色 | 周期 | 亮/灭    | 骑车感知      |
 * |----------|------|------|----------|---------------|
 * | Safe     | 灰   | —    | 恒亮     | 安全          |
 * | Warning  | 黄   | 1000 | 700/300  | 慢悠悠有车    |
 * | Alert    | 黄   | 500  | 300/200  | 有车在靠近    |
 * | Critical | 红   | 200  | 100/100  | 马上撞 ！！   |
 *
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

    // ── pulse ─────────────────────────────────────────────

    private fun startPulse(level: AlertLevel) {
        val (color, periodMs, onFraction) = when (level) {
            AlertLevel.Warning  -> Triple(COLOR_WARNING,  1000, 0.70f)
            AlertLevel.Alert    -> Triple(COLOR_WARNING,   500, 0.60f)
            AlertLevel.Critical -> Triple(COLOR_CRITICAL,  200, 0.50f)
            else -> return
        }

        val dimmed = dimColor(color, 0.15f)
        val onMs = (periodMs * onFraction).toLong()
        val offMs = periodMs - onMs

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = onMs
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                val bright = blend(dimmed, color, a.animatedValue as Float)
                onColorUpdate(bright)
            }
            start()
        }
    }

    // ── safety ────────────────────────────────────────────

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

    // ── color utils ───────────────────────────────────────

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
