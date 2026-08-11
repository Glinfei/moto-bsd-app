package com.motobsd.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.motobsd.model.AlertLevel
import com.motobsd.model.TargetObject
import com.motobsd.ui.theme.AlertOrange
import com.motobsd.ui.theme.CriticalRed
import com.motobsd.ui.theme.SafeGray
import com.motobsd.ui.theme.WarningYellow
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 雷达视图：后向扇形，占满画布。
 *
 * 器件（车尾）位于画布顶部中央，扇区向下张开、弧线撑满宽度，
 * 前方死区只保留一条窄边作为方向参考。
 * 角度约定与协议一致：负=左、正=右、0=正后方；屏幕上=车头方向。
 *
 * 颜色语义与告警一致：靠近且近=红/橙/黄，远离/静止=灰。
 * 扇区左右边界内侧的小圆点表示 alert_status 的左右有无目标。
 */
@Composable
fun RadarView(
    targets: List<TargetObject>,
    leftAlert: AlertLevel,
    rightAlert: AlertLevel,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        // 器件位置：顶部中央；扇区向下张开
        val apex = Offset(size.width / 2f, 22.dp.toPx())
        val halfFov = RADAR_FOV_DEGREES / 2f

        // 半径取"宽度约束 / 高度约束"的较小值，让扇区尽量大且不越界
        val maxRadiusByWidth =
            (size.width / 2f - 14.dp.toPx()) / sin(Math.toRadians(halfFov.toDouble())).toFloat()
        val maxRadiusByHeight = size.height - 48.dp.toPx()
        val radius = min(maxRadiusByWidth, maxRadiusByHeight).coerceAtLeast(1f)

        // 显示范围：至少 15m，动态扩展到最远目标（上限 50m，对应 AT6010 汽车 50m 探测规格）
        val maxRange = max(15f, targets.maxOfOrNull { it.rangeMeters } ?: 15f)
            .coerceAtMost(50f)

        drawRadarFrame(apex, radius, maxRange)
        drawDirectionLabels(apex, radius)
        drawDeviceMarker(apex)

        if (targets.isEmpty()) {
            // 空态文字放在扇区中心（后侧），不遮挡器件标记
            drawText("无目标", Offset(apex.x, apex.y + radius * 0.45f), SafeGray, 13.dp.toPx())
        } else {
            targets.forEach { t -> drawTarget(apex, radius, maxRange, t) }
            targets.minByOrNull { it.rangeM }?.let { nearest ->
                drawNearestLabel(apex, radius, maxRange, nearest)
            }
        }

        // 左右有无目标的 presence 标记（alert_status）
        drawSideMarker(isLeft = true, level = leftAlert, apex = apex, radius = radius)
        drawSideMarker(isLeft = false, level = rightAlert, apex = apex, radius = radius)
    }
}

/** 后向探测扇区角度（0°=正后方，±FOV/2 为左右边界）。AT6010 BSD 默认 ±75°，可按实际标定调整。 */
private const val RADAR_FOV_DEGREES = 150f

// ── 绘制 ──────────────────────────────────────────────

private fun DrawScope.drawRadarFrame(apex: Offset, radius: Float, maxRange: Float) {
    val startAngle = 90f - RADAR_FOV_DEGREES / 2f
    val sweepAngle = RADAR_FOV_DEGREES

    // 扇区底色
    drawArc(
        color = Color(0x142196F3),
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = true,
        topLeft = Offset(apex.x - radius, apex.y - radius),
        size = Size(radius * 2, radius * 2),
    )

    // 同心圆（只画在扇区内的弧段）
    for (i in 1..3) {
        val r = radius * i / 3f
        drawArc(
            color = Color.Gray.copy(alpha = 0.35f),
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(apex.x - r, apex.y - r),
            size = Size(r * 2, r * 2),
            style = Stroke(1.dp.toPx()),
        )
    }

    // 扇区左右边界 + 后向轴线
    val boundaryColor = Color.Gray.copy(alpha = 0.35f)
    drawLine(
        color = boundaryColor,
        start = apex,
        end = polarPoint(apex, radius, -RADAR_FOV_DEGREES / 2f),
        strokeWidth = 1.dp.toPx(),
    )
    drawLine(
        color = boundaryColor,
        start = apex,
        end = polarPoint(apex, radius, RADAR_FOV_DEGREES / 2f),
        strokeWidth = 1.dp.toPx(),
    )
    drawLine(
        color = Color.Gray.copy(alpha = 0.2f),
        start = apex,
        end = Offset(apex.x, apex.y + radius),
        strokeWidth = 1.dp.toPx(),
    )

    // 距离刻度说明（扇区右下角）
    drawText(
        "范围 ${maxRange.toInt()}m",
        Offset(apex.x + radius * 0.55f, apex.y + radius * 0.72f),
        Color.Gray.copy(alpha = 0.6f),
        9.dp.toPx(),
    )
}

private fun DrawScope.drawDirectionLabels(apex: Offset, radius: Float) {
    val textSize = 11.dp.toPx()
    // 前方无探测，弱化显示仅为定位方向
    drawText(
        "前方",
        Offset(apex.x, apex.y - 16.dp.toPx()),
        Color.Gray.copy(alpha = 0.35f),
        textSize,
    )
    drawText(
        "后方",
        Offset(apex.x, apex.y + radius + 18.dp.toPx()),
        Color.Gray.copy(alpha = 0.7f),
        textSize,
    )
    drawText(
        "左",
        polarPoint(apex, radius + 14.dp.toPx(), -RADAR_FOV_DEGREES / 2f),
        Color.Gray.copy(alpha = 0.7f),
        textSize,
    )
    drawText(
        "右",
        polarPoint(apex, radius + 14.dp.toPx(), RADAR_FOV_DEGREES / 2f),
        Color.Gray.copy(alpha = 0.7f),
        textSize,
    )
}

/** 器件/车尾位置：扇区顶点 */
private fun DrawScope.drawDeviceMarker(apex: Offset) {
    drawCircle(color = Color(0xFF37474F), radius = 6.dp.toPx(), center = apex)
    drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = apex)
}

private fun DrawScope.drawTarget(apex: Offset, radius: Float, maxRange: Float, t: TargetObject) {
    val pos = targetPos(apex, radius, maxRange, t)
    val color = targetColor(t)
    val speedBoost = if (t.velocity > 0) min(4f, t.velocity * 0.5f) else 0f
    val dotRadius = (8f + speedBoost).dp.toPx()

    drawCircle(color = color, radius = dotRadius, center = pos)
    drawCircle(
        color = Color.Black.copy(alpha = 0.35f),
        radius = dotRadius,
        center = pos,
        style = Stroke(1.5.dp.toPx()),
    )
}

private fun DrawScope.drawNearestLabel(
    apex: Offset,
    radius: Float,
    maxRange: Float,
    t: TargetObject,
) {
    val pos = targetPos(apex, radius, maxRange, t)
    val dotRadius = (8f + if (t.velocity > 0) min(4f, t.velocity * 0.5f) else 0f).dp.toPx()
    val approach = when {
        t.velocity > 0 -> "靠近"
        t.velocity < 0 -> "远离"
        else -> "静止"
    }
    val label = String.format("%.1fm · %dm/s %s", t.rangeMeters, t.velocity, approach)
    val labelTextSize = 11.dp.toPx()

    var labelX = pos.x
    var labelY = pos.y - dotRadius - 8.dp.toPx()
    val approxW = label.length * labelTextSize * 0.55f
    labelX = labelX.coerceIn(approxW / 2f, size.width - approxW / 2f)
    labelY = labelY.coerceIn(labelTextSize, size.height - 2.dp.toPx())

    // 深色描边 + 彩色填充，保证任何背景下可读
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.Black.copy(alpha = 0.65f).toArgb()
        textSize = labelTextSize
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeWidth = 3.dp.toPx()
    }
    drawContext.canvas.nativeCanvas.drawText(label, labelX, labelY, strokePaint)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = targetColor(t).toArgb()
        textSize = labelTextSize
        textAlign = Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(label, labelX, labelY, fillPaint)
}

/** presence 标记：扇区左右边界内侧 */
private fun DrawScope.drawSideMarker(isLeft: Boolean, level: AlertLevel, apex: Offset, radius: Float) {
    if (level == AlertLevel.Safe) return
    val angle = if (isLeft) -RADAR_FOV_DEGREES / 2f * 0.72f else RADAR_FOV_DEGREES / 2f * 0.72f
    val pos = polarPoint(apex, radius * 0.55f, angle)
    val color = alertColor(level)
    drawCircle(color = color, radius = 6.dp.toPx(), center = pos)
    drawCircle(
        color = Color.Black.copy(alpha = 0.3f),
        radius = 6.dp.toPx(),
        center = pos,
        style = Stroke(1.dp.toPx()),
    )
}

// ── 辅助 ──────────────────────────────────────────────

private fun DrawScope.polarPoint(origin: Offset, radius: Float, angleDeg: Float): Offset {
    val a = Math.toRadians(angleDeg.toDouble())
    return Offset(
        x = origin.x + (sin(a) * radius).toFloat(),
        y = origin.y + (cos(a) * radius).toFloat(),
    )
}

private fun DrawScope.targetPos(apex: Offset, radius: Float, maxRange: Float, t: TargetObject): Offset {
    val frac = (t.rangeMeters / maxRange).coerceIn(0.05f, 1f)
    // 越界目标（噪声）钳制到扇区边界，避免画到"前方"区域
    val clampedAngle = t.angleDeg.toFloat().coerceIn(-RADAR_FOV_DEGREES / 2f, RADAR_FOV_DEGREES / 2f)
    val a = Math.toRadians(clampedAngle.toDouble())
    return Offset(
        x = apex.x + (sin(a) * radius * frac).toFloat(),
        y = apex.y + (cos(a) * radius * frac).toFloat(),
    )
}

private fun targetColor(t: TargetObject): Color = when {
    t.velocity > 0 && t.rangeM <= 5 -> CriticalRed
    t.velocity > 0 && t.rangeM <= 10 -> AlertOrange
    t.velocity > 0 -> WarningYellow
    else -> SafeGray
}

private fun alertColor(level: AlertLevel): Color = when (level) {
    AlertLevel.Safe -> SafeGray
    AlertLevel.Warning -> WarningYellow
    AlertLevel.Alert -> AlertOrange
    AlertLevel.Critical -> CriticalRed
}

private fun DrawScope.drawText(text: String, pos: Offset, color: Color, textSize: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        this.textSize = textSize
        textAlign = Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(text, pos.x, pos.y, paint)
}
