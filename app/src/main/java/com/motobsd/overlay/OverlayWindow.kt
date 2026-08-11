package com.motobsd.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.motobsd.data.overlay.OverlayRepository
import com.motobsd.model.LightBarOrientation
import com.motobsd.model.OverlayConfig

/**
 * 悬浮窗管理器：左右两条弧形灯带，固定贴屏幕边缘、不可触摸。
 * 威胁度变化由 [setThreat] 直通到视图，配置变更走 [applyConfig]。
 */
class OverlayWindow(
    private val context: Context,
    @Suppress("unused") private val overlayRepository: OverlayRepository,
) {
    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val leftView = BsdIndicatorView(context, BsdIndicatorView.Side.Left)
    private val rightView = BsdIndicatorView(context, BsdIndicatorView.Side.Right)

    private var isAttached = false
    private var screenW: Int = 0
    private var screenH: Int = 0
    /** 灯带框架尺寸：Android 11+ 使用系统最大窗口区域，自适应状态栏/导航栏/挖孔 */
    private var barScreenW: Int = 0
    private var barScreenH: Int = 0
    private var config: OverlayConfig = OverlayConfig()
    private var keepScreenOn: Boolean = false

    // ── public API ────────────────────────────────────────

    fun show(config: OverlayConfig = OverlayConfig()) {
        this.config = config
        updateScreenSize()
        doShow()
    }

    private fun doShow() {
        if (isAttached) {
            updatePositions()
            return
        }
        try {
            addView(leftView)
            addView(rightView)
            isAttached = true
            leftView.applyConfig(config)
            rightView.applyConfig(config)
            applyKeepScreenOn()
            updatePositions()
        } catch (_: Exception) {
            // 无悬浮窗权限等异常：不崩溃，等待权限授予后重新启动 Service
            isAttached = false
        }
    }

    fun hide() {
        if (!isAttached) return
        try { wm.removeView(leftView) } catch (_: Exception) {}
        try { wm.removeView(rightView) } catch (_: Exception) {}
        isAttached = false
    }

    /** 威胁度 0~1 直通灯带视图 */
    fun setThreat(side: BsdIndicatorView.Side, threat: Float) {
        when (side) {
            BsdIndicatorView.Side.Left -> leftView.setThreat(threat)
            BsdIndicatorView.Side.Right -> rightView.setThreat(threat)
        }
    }

    /** 断线时灯带灰色呼吸，与"安全"区分 */
    fun setConnected(connected: Boolean) {
        leftView.setConnected(connected)
        rightView.setConnected(connected)
    }

    fun applyConfig(config: OverlayConfig) {
        this.config = config
        leftView.applyConfig(config)
        rightView.applyConfig(config)
        applyKeepScreenOn()
        updatePositions()
    }

    /** 骑行模式：悬浮窗可见期间保持屏幕常亮（窗口未创建时先记住状态） */
    fun setKeepScreenOn(enabled: Boolean) {
        if (keepScreenOn == enabled) return
        keepScreenOn = enabled
        applyKeepScreenOn()
    }

    /** 重读屏幕物理尺寸并更新位置。旋转 / 切换横竖屏时触发。 */
    fun refresh() {
        updateScreenSize()
        updatePositions()
    }

    // ── WindowManager ─────────────────────────────────────

    private val isVertical: Boolean
        get() = config.lightBarOrientation == LightBarOrientation.Vertical

    private fun addView(view: View) {
        val (w, h) = dims()
        val params = WindowManager.LayoutParams(
            w, h,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (isVertical) {
                if (view === leftView) 0 else barScreenW - w
            } else 0
            y = if (isVertical) 0 else {
                if (view === leftView) 0 else barScreenH - h
            }
        }
        wm.addView(view, params)
    }

    private fun updatePositions() {
        val (w, h) = dims()
        listOf(leftView to true, rightView to false).forEach { (view, isLeft) ->
            (view.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
                lp.width = w
                lp.height = h
                lp.x = if (isVertical) {
                    if (isLeft) 0 else barScreenW - w
                } else 0
                lp.y = if (isVertical) 0 else {
                    if (isLeft) 0 else barScreenH - h
                }
                try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
            }
        }
    }

    /** 视图尺寸：宽度/高度 = 2×厚度，为内缘弧度留出空间 */
    private fun dims(): Pair<Int, Int> {
        val thickness = (config.size.dp * context.resources.displayMetrics.density).toInt()
        return if (isVertical) {
            (thickness * 2) to barScreenH
        } else {
            barScreenW to (thickness * 2)
        }
    }

    private fun applyKeepScreenOn() {
        listOf(leftView, rightView).forEach { view ->
            (view.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
                lp.flags = if (keepScreenOn) {
                    lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                } else {
                    lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                }
                try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
            }
        }
    }

    private fun updateScreenSize() {
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        // 灯带框架：优先使用系统最大窗口区域（自动排除不透明系统栏、适配透明栏）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            barScreenW = bounds.width()
            barScreenH = bounds.height()
        } else {
            barScreenW = screenW
            barScreenH = screenH
        }
    }
}
