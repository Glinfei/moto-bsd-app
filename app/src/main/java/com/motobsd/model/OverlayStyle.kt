package com.motobsd.model

/**
 * 悬浮窗图标样式。
 */
enum class OverlayStyle(val label: String) {
    LightBar("光带"),
    Dot("圆点"),
    Bar("竖条"),
    Arrow("箭头"),
}

/**
 * 悬浮窗图标大小。
 */
enum class OverlaySize(val label: String, val dp: Float) {
    Small("小", 28f),
    Medium("中", 40f),
    Large("大", 56f),
}

/**
 * 光带布局方向。
 *
 * 用户手动选择，不自动感知屏幕旋转：
 * - [Vertical]  竖屏布局 —— 光带贴屏幕左右边缘、满屏高。
 * - [Horizontal] 横屏布局 —— 光带贴屏幕上下边缘、横向横条。
 */
enum class LightBarOrientation(val label: String) {
    Vertical("左右边缘"),
    Horizontal("上下边缘"),
}

/**
 * 悬浮窗配置 — 持久化到 DataStore。
 */
data class OverlayConfig(
    val style: OverlayStyle = OverlayStyle.LightBar,
    val size: OverlaySize = OverlaySize.Medium,
    /** 0-100，默认 60% */
    val alpha: Int = 60,
    /** 左右反转（适配雷达安装方向） */
    val swapLeftRight: Boolean = false,
    /** 光带布局方向（用户手动切换） */
    val lightBarOrientation: LightBarOrientation = LightBarOrientation.Vertical,
    /** left dot 自定义位置 (fraction of screen), null=用默认 */
    val leftXFraction: Float? = null,
    val leftYFraction: Float? = null,
    /** right dot 自定义位置, null=用默认 */
    val rightXFraction: Float? = null,
    val rightYFraction: Float? = null,
)
