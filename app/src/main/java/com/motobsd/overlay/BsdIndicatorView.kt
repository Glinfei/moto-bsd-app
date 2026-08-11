package com.motobsd.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import com.motobsd.model.LightBarOrientation
import com.motobsd.model.OverlayConfig

/**
 * 单个盲区指示 View（left / right 侧各一个）。
 *
 * 形态为"充电弧"式弧形灯带：外缘贴屏幕边缘（直线），内缘是向屏幕中心凸起的弧线
 * （`|)` / `(|`），中间最宽、向两端收窄。由威胁度（0~1）连续驱动：
 * - 颜色：黄 → 橙 → 红 连续渐变
 * - 亮度：威胁越高越亮
 * - 长度：弧从中间一小段向两端延伸（威胁越高覆盖越大）
 * - Safe（无目标）全透明；断线灰色慢速呼吸
 */
class BsdIndicatorView(
    context: Context,
    val side: Side,
) : View(context) {

    enum class Side(val label: String) {
        Left("左"),
        Right("右"),
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ── 状态 ─────────────────────────────────────────────

    private var threat: Float = 0f
    private var currentAlpha: Float = 0.6f
    private var thicknessDp: Float = OverlayConfig().size.dp
    private var orientation: LightBarOrientation = LightBarOrientation.Vertical
    private var connected: Boolean = true

    private var breathAlpha: Float = 1f
    private var breathAnimator: ValueAnimator? = null

    // ── Public API ──────────────────────────────────────

    /** 设置威胁度 0~1，驱动弧长/亮度/颜色连续变化 */
    fun setThreat(value: Float) {
        threat = value.coerceIn(0f, 1f)
        invalidate()
    }

    /** 断线 → 灰色慢速呼吸；重连 → 恢复威胁度显示 */
    fun setConnected(connected: Boolean) {
        if (this.connected == connected) return
        this.connected = connected
        if (!connected) {
            startBreath()
        } else {
            breathAnimator?.cancel()
            breathAnimator = null
            breathAlpha = 1f
        }
        invalidate()
    }

    fun applyConfig(config: OverlayConfig) {
        currentAlpha = config.alpha / 100f
        thicknessDp = config.size.dp
        orientation = config.lightBarOrientation
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        breathAnimator?.cancel()
    }

    private fun startBreath() {
        breathAnimator = ValueAnimator.ofFloat(0.35f, 0.85f).apply {
            duration = 2000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                breathAlpha = a.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ── 绘制 ─────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when {
            !connected -> drawBand(canvas, COLOR_SAFE, breathAlpha, fullExtent = true)
            threat <= 0.01f -> Unit // 安全：全透明
            else -> {
                val brightness = 0.5f + 0.5f * threat
                drawBand(canvas, threatColor(threat), brightness)
            }
        }
    }

    private fun drawBand(canvas: Canvas, baseColor: Int, brightness: Float, fullExtent: Boolean = false) {
        val w = width.toFloat()
        val h = height.toFloat()
        val density = resources.displayMetrics.density
        val thickness = thicknessDp * density
        // 弧长：威胁越大，纵向/横向覆盖越大（0.3 → 1.0）；断线时全幅灰色呼吸
        val extent = if (fullExtent) 1f else 0.3f + 0.7f * threat
        // 内缘弧度：威胁越大弧越鼓（0.8 → 1.5 倍厚度）
        val bulge = thickness * (0.8f + 0.7f * threat)

        val path = Path()
        val shader: LinearGradient

        if (orientation == LightBarOrientation.Vertical) {
            val total = h * extent
            val yTop = (h - total) / 2f
            val yBottom = yTop + total
            if (side == Side.Left) {
                // 外缘贴屏幕左边缘（直线），内缘向右凸 → `|)`
                path.moveTo(0f, yTop)
                path.lineTo(0f, yBottom)
                path.quadTo(bulge, (yTop + yBottom) / 2f, 0f, yTop)
                shader = LinearGradient(
                    0f, 0f, bulge, 0f,
                    baseColor, Color.TRANSPARENT, Shader.TileMode.CLAMP,
                )
            } else {
                // 右边缘镜像 → `(|`
                path.moveTo(w, yTop)
                path.lineTo(w, yBottom)
                path.quadTo(w - bulge, (yTop + yBottom) / 2f, w, yTop)
                shader = LinearGradient(
                    w, 0f, w - bulge, 0f,
                    baseColor, Color.TRANSPARENT, Shader.TileMode.CLAMP,
                )
            }
        } else {
            val total = w * extent
            val xLeft = (w - total) / 2f
            val xRight = xLeft + total
            if (side == Side.Left) {
                // 顶边：外缘贴屏幕顶（直线），内缘向下凸
                path.moveTo(xLeft, 0f)
                path.lineTo(xRight, 0f)
                path.quadTo((xLeft + xRight) / 2f, bulge, xLeft, 0f)
                shader = LinearGradient(
                    0f, 0f, 0f, bulge,
                    baseColor, Color.TRANSPARENT, Shader.TileMode.CLAMP,
                )
            } else {
                // 底边：外缘贴底，内缘向上凸
                path.moveTo(xLeft, h)
                path.lineTo(xRight, h)
                path.quadTo((xLeft + xRight) / 2f, h - bulge, xLeft, h)
                shader = LinearGradient(
                    0f, h, 0f, h - bulge,
                    baseColor, Color.TRANSPARENT, Shader.TileMode.CLAMP,
                )
            }
        }
        path.close()

        val alpha = (currentAlpha * brightness * 255).toInt().coerceIn(0, 255)
        paint.shader = shader
        paint.color = Color.argb(
            alpha,
            Color.red(baseColor),
            Color.green(baseColor),
            Color.blue(baseColor),
        )
        canvas.drawPath(path, paint)
        paint.shader = null
    }

    private fun threatColor(t: Float): Int = when {
        t < 0.5f -> lerpColor(COLOR_WARNING, COLOR_ALERT, t / 0.5f)
        else -> lerpColor(COLOR_ALERT, COLOR_CRITICAL, (t - 0.5f) / 0.5f)
    }

    private fun lerpColor(c1: Int, c2: Int, ratio: Float): Int {
        val r = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * ratio).toInt()
        val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * ratio).toInt()
        val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * ratio).toInt()
        return Color.rgb(r, g, b)
    }

    companion object {
        val COLOR_SAFE = Color.parseColor("#9E9E9E")
        val COLOR_WARNING = Color.parseColor("#FFC107")
        val COLOR_ALERT = Color.parseColor("#FF9800")
        val COLOR_CRITICAL = Color.parseColor("#F44336")
    }
}
