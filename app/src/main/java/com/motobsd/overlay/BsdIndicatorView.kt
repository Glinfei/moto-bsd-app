package com.motobsd.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.motobsd.model.AlertLevel
import com.motobsd.model.LightBarOrientation
import com.motobsd.model.OverlayConfig
import com.motobsd.model.OverlayStyle

/**
 * 单个盲区指示器 View（left 或 right 侧各一个）。
 *
 * 光带模式：不经过 AlertAnimator，直接从 alert level 取色，Safe 时全透明。
 * 圆点/竖条/箭头：通过 AlertAnimator 管理颜色动画。
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
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    // ── 图标模式：AlertAnimator ──────────────────────────

    private val animator = AlertAnimator { color ->
        paint.color = color
        borderPaint.color = color
        invalidate()
    }

    // ── 光带模式：独立颜色 + 脉冲 alpha ──────────────────

    private var lightBarLevel: AlertLevel = AlertLevel.Safe
    private var lightBarPulseAlpha: Float = 0f
    private var pulseAnimator: ValueAnimator? = null

    // ── 通用状态 ─────────────────────────────────────────

    private var currentAlpha: Float = 0.6f
    private var showLabel: Boolean = false
    private var currentStyle: OverlayStyle = OverlayStyle.LightBar
    private var orientation: LightBarOrientation = LightBarOrientation.Vertical

    // ── Public API ──────────────────────────────────────

    fun setAlertLevel(level: AlertLevel) {
        if (currentStyle == OverlayStyle.LightBar) {
            setLightBarLevel(level)
        } else {
            animator.setLevel(level)
        }
    }

    private fun setLightBarLevel(level: AlertLevel) {
        if (level == lightBarLevel) return
        lightBarLevel = level

        pulseAnimator?.cancel()
        pulseAnimator = null

        when (level) {
            AlertLevel.Safe -> {
                lightBarPulseAlpha = 0f
            }
            AlertLevel.Warning, AlertLevel.Alert -> {
                lightBarPulseAlpha = 1f  // 常亮
            }
            AlertLevel.Critical -> {
                startLightBarPulse()
            }
        }
        invalidate()
    }

    private fun startLightBarPulse() {
        pulseAnimator = ValueAnimator.ofFloat(0.3f, 1f).apply {
            duration = 200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                lightBarPulseAlpha = a.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun applyConfig(config: OverlayConfig) {
        currentAlpha = config.alpha / 100f
        currentStyle = config.style
        orientation = config.lightBarOrientation
        // 切样式时重置光带状态
        if (config.style != OverlayStyle.LightBar) {
            pulseAnimator?.cancel()
            pulseAnimator = null
            lightBarLevel = AlertLevel.Safe
            lightBarPulseAlpha = 0f
        }
        invalidate()
    }

    fun setLabelVisible(visible: Boolean) {
        if (showLabel != visible) {
            showLabel = visible
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.release()
        pulseAnimator?.cancel()
    }

    // ── Drawing ─────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        when (currentStyle) {
            OverlayStyle.LightBar -> drawLightBar(canvas, w, h)
            OverlayStyle.Dot -> drawDotStyle(canvas, w, h)
            OverlayStyle.Bar -> drawBarStyle(canvas, w, h)
            OverlayStyle.Arrow -> drawArrowStyle(canvas, w, h)
        }
    }

    // ── LightBar ────────────────────────────────────────

    private fun drawLightBar(canvas: Canvas, w: Float, h: Float) {
        if (lightBarLevel == AlertLevel.Safe) {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            return
        }

        val baseColor = when (lightBarLevel) {
            AlertLevel.Warning, AlertLevel.Alert -> AlertAnimator.COLOR_WARNING
            AlertLevel.Critical -> AlertAnimator.COLOR_CRITICAL
            else -> return
        }

        val alpha = ((currentAlpha * lightBarPulseAlpha) * 255).toInt().coerceIn(0, 255)
        val solidColor = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
        val transColor = Color.argb(0, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))

        val gradient = when {
            // 横屏：光带沿屏幕上下边缘（横向横条），垂直方向由屏幕边缘向屏内渐隐
            orientation == LightBarOrientation.Horizontal ->
                if (side == Side.Left) {
                    // 顶部条：y=0 贴屏幕顶边（实色）→ y=h 向内渐隐
                    LinearGradient(0f, 0f, 0f, h, solidColor, transColor, Shader.TileMode.CLAMP)
                } else {
                    // 底部条：y=h 贴屏幕底边（实色）→ y=0 向内渐隐
                    LinearGradient(0f, 0f, 0f, h, transColor, solidColor, Shader.TileMode.CLAMP)
                }
            // 竖屏：光带贴左右边缘（纵向竖条），水平方向由屏幕边缘向屏内渐隐
            side == Side.Left ->
                // 左条：x=0 贴屏幕左边（实色）→ x=w 向内渐隐
                LinearGradient(0f, 0f, w, 0f, solidColor, transColor, Shader.TileMode.CLAMP)
            else ->
                // 右条：x=w 贴屏幕右边（实色）→ x=0 向内渐隐
                LinearGradient(0f, 0f, w, 0f, transColor, solidColor, Shader.TileMode.CLAMP)
        }

        paint.shader = gradient
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    // ── Dot / Bar / Arrow (unchanged) ───────────────────

    private fun drawDotStyle(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f; val cy = h / 2f; val r = minOf(w, h) / 2f
        paint.alpha = (currentAlpha * 0.25f * 255).toInt()
        canvas.drawCircle(cx, cy, r * 1.5f, paint)
        paint.alpha = (currentAlpha * 255).toInt()
        canvas.drawCircle(cx, cy, r * 0.85f, paint)
        highlightPaint.color = Color.argb((currentAlpha * 50).toInt(), 255, 255, 255)
        canvas.drawCircle(cx - r * 0.15f, cy - r * 0.2f, r * 0.3f, highlightPaint)

        val labelSize = r * 0.55f
        textPaint.textSize = labelSize
        textPaint.alpha = (currentAlpha * 200).toInt()
        canvas.drawText(side.label, cx, cy + labelSize / 3f, textPaint)
    }

    private fun drawBarStyle(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f; val cy = h / 2f; val r = minOf(w, h) / 2f
        val barW = w * 0.4f; val barH = h * 0.9f
        val rect = RectF(cx - barW / 2f, cy - barH / 2f, cx + barW / 2f, cy + barH / 2f)
        paint.alpha = (currentAlpha * 255).toInt()
        canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)

        val labelSize = r * 0.55f
        textPaint.textSize = labelSize
        textPaint.alpha = (currentAlpha * 200).toInt()
        canvas.drawText(side.label, cx, cy + labelSize / 3f, textPaint)
    }

    private fun drawArrowStyle(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f; val cy = h / 2f; val r = minOf(w, h) / 2f
        val aw = w * 0.7f; val ah = h * 0.5f
        val path = Path()
        if (side == Side.Left) {
            path.moveTo(cx - aw / 2f, cy)
            path.lineTo(cx + aw / 2f, cy - ah / 2f)
            path.lineTo(cx + aw / 2f, cy + ah / 2f)
        } else {
            path.moveTo(cx + aw / 2f, cy)
            path.lineTo(cx - aw / 2f, cy - ah / 2f)
            path.lineTo(cx - aw / 2f, cy + ah / 2f)
        }
        path.close()
        paint.alpha = (currentAlpha * 255).toInt()
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)

        val labelSize = r * 0.55f
        textPaint.textSize = labelSize
        textPaint.alpha = (currentAlpha * 200).toInt()
        canvas.drawText(side.label, cx, cy + labelSize / 3f, textPaint)
    }
}
