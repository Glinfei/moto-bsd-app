package com.motobsd.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.motobsd.model.AlertLevel
import com.motobsd.model.OverlayConfig
import com.motobsd.model.OverlayStyle

/**
 * 单个盲区指示器 View（left 或 right 侧各一个）。
 * 由 WindowManager 直接 add 到 Overlay 层。
 */
class BsdIndicatorView(
    context: Context,
    private val side: Side,
) : View(context) {

    enum class Side(val label: String) {
        Left("左"),
        Right("右"),
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val animator = AlertAnimator { color ->
        paint.color = color
        borderPaint.color = color
        invalidate()
    }

    private var currentAlpha: Float = 0.6f
    private var currentLevel: AlertLevel = AlertLevel.Safe
    private var showLabel: Boolean = false
    private var currentStyle: OverlayStyle = OverlayStyle.Dot

    var onLongPress: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null

    /** 外部调用：设置告警级别 */
    fun setAlertLevel(level: AlertLevel) {
        currentLevel = level
        animator.setLevel(level)
    }

    fun applyConfig(config: OverlayConfig) {
        currentAlpha = config.alpha / 100f
        currentStyle = config.style
        invalidate()
    }

    /** 拖拽时显示标签，松手隐藏 */
    fun setLabelVisible(visible: Boolean) {
        if (showLabel != visible) {
            showLabel = visible
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.release()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f

        when (currentStyle) {
            OverlayStyle.Dot -> drawDotStyle(canvas, cx, cy, r)
            OverlayStyle.Bar -> drawBarStyle(canvas, cx, cy, w, h)
            OverlayStyle.Arrow -> drawArrowStyle(canvas, cx, cy, w, h)
        }

        // 左右标签文字
        val labelSize = if (showLabel) r * 0.7f else r * 0.55f
        textPaint.textSize = labelSize
        textPaint.alpha = if (showLabel) 255 else (currentAlpha * 200).toInt()
        canvas.drawText(side.label, cx, cy + labelSize / 3f, textPaint)
    }

    private fun drawDotStyle(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.alpha = (currentAlpha * 0.25f * 255).toInt()
        canvas.drawCircle(cx, cy, r * 1.5f, paint)
        paint.alpha = (currentAlpha * 255).toInt()
        canvas.drawCircle(cx, cy, r * 0.85f, paint)
        paint.color = Color.argb((currentAlpha * 50).toInt(), 255, 255, 255)
        canvas.drawCircle(cx - r * 0.15f, cy - r * 0.2f, r * 0.3f, paint)
    }

    private fun drawBarStyle(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        val barW = w * 0.4f
        val barH = h * 0.9f
        val rect = RectF(cx - barW / 2f, cy - barH / 2f, cx + barW / 2f, cy + barH / 2f)
        paint.alpha = (currentAlpha * 255).toInt()
        canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
    }

    private fun drawArrowStyle(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        val aw = w * 0.7f
        val ah = h * 0.5f
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
    }
}
