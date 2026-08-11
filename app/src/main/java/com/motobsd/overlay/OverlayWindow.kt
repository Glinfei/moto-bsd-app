package com.motobsd.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import com.motobsd.MainActivity
import com.motobsd.data.overlay.OverlayRepository
import com.motobsd.model.AlertLevel
import com.motobsd.model.LightBarOrientation
import com.motobsd.model.OverlayConfig
import com.motobsd.model.OverlayStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

class OverlayWindow(
    private val context: Context,
    private val overlayRepository: OverlayRepository,
) {
    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val leftView = BsdIndicatorView(context, BsdIndicatorView.Side.Left)
    private val rightView = BsdIndicatorView(context, BsdIndicatorView.Side.Right)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var isAttached = false
    private var screenW: Int = 0
    private var screenH: Int = 0
    /** 灯带框架尺寸：Android 11+ 使用系统最大窗口区域，自适应状态栏/导航栏/挖孔 */
    private var barScreenW: Int = 0
    private var barScreenH: Int = 0
    private var config: OverlayConfig = OverlayConfig()

    private var savedLeftX: Int? = null
    private var savedLeftY: Int? = null
    private var savedRightX: Int? = null
    private var savedRightY: Int? = null
    /** 保存位置时的屏幕物理尺寸，用于旋转后按比例换算坐标 */
    private var savedLeftW: Int = 0
    private var savedLeftH: Int = 0
    private var savedRightW: Int = 0
    private var savedRightH: Int = 0

    private var currentLeftLevel: AlertLevel = AlertLevel.Safe
    private var currentRightLevel: AlertLevel = AlertLevel.Safe
    private var currentBattery: Int = 0
    private var connected: Boolean = true

    // ── public API ────────────────────────────────────────

    fun show(config: OverlayConfig = OverlayConfig()) {
        this.config = config
        updateScreenSize()
        scope.launch {
            val (lx, ly) = overlayRepository.loadPosition(com.motobsd.data.overlay.BsdSide.Left)
            val (rx, ry) = overlayRepository.loadPosition(com.motobsd.data.overlay.BsdSide.Right)
            val (lw, lh) = overlayRepository.loadScreenDims(com.motobsd.data.overlay.BsdSide.Left)
            val (rw, rh) = overlayRepository.loadScreenDims(com.motobsd.data.overlay.BsdSide.Right)
            savedLeftX = lx; savedLeftY = ly
            savedRightX = rx; savedRightY = ry
            savedLeftW = lw; savedLeftH = lh
            savedRightW = rw; savedRightH = rh
            doShow()
        }
    }

    private fun doShow() {
        if (isAttached) { updatePositions(); return }
        try {
            addView(leftView, isLeft = true)
            addView(rightView, isLeft = false)
            isAttached = true
            leftView.applyConfig(config)
            rightView.applyConfig(config)
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

    fun setAlertLevel(side: BsdIndicatorView.Side, level: AlertLevel) {
        when (side) {
            BsdIndicatorView.Side.Left -> { currentLeftLevel = level; leftView.setAlertLevel(level) }
            BsdIndicatorView.Side.Right -> { currentRightLevel = level; rightView.setAlertLevel(level) }
        }
    }

    fun setBattery(pct: Int) { currentBattery = pct }

    /** 断线时悬浮指示切换为灰色呼吸，与"安全"区分 */
    fun setConnected(connected: Boolean) {
        if (this.connected == connected) return
        this.connected = connected
        leftView.setConnected(connected)
        rightView.setConnected(connected)
    }

    fun applyConfig(config: OverlayConfig) {
        this.config = config

        // 同步触摸标志：光带不可触摸，图标模式可拖拽/双击/长按
        listOf(leftView to true, rightView to false).forEach { (view, isLeft) ->
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            if (isLightBar) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                view.setOnTouchListener(null)
            } else {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                view.setupTouch(isLeft, lp)
            }
            try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
        }

        leftView.applyConfig(config)
        rightView.applyConfig(config)
        // 样式切换会重置视图内部状态，这里重新应用当前告警级别
        leftView.setAlertLevel(currentLeftLevel)
        rightView.setAlertLevel(currentRightLevel)
        updatePositions()
    }

    /** 重读屏幕物理尺寸并更新位置。旋转 / 切换横竖屏时触发。 */
    fun refresh() {
        updateScreenSize()
        updatePositions()
    }

    /** 清除保存的位置并回到默认位置（用户点「重置为默认位置」触发）。 */
    fun resetPositions() {
        savedLeftX = null; savedLeftY = null
        savedRightX = null; savedRightY = null
        savedLeftW = 0; savedLeftH = 0
        savedRightW = 0; savedRightH = 0
        scope.launch { overlayRepository.resetPositions() }
        updatePositions()
    }

    // ── WindowManager ─────────────────────────────────────

    private val isLightBar: Boolean get() = config.style == OverlayStyle.LightBar
    private val isVertical: Boolean get() = config.lightBarOrientation == LightBarOrientation.Vertical

    private fun addView(view: View, isLeft: Boolean) {
        val (w, h) = dims()
        val params = WindowManager.LayoutParams(
            w, h,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (isLightBar) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (isLightBar) lightBarX(isLeft, w) else defaultX(isLeft, w)
            y = if (isLightBar) lightBarY(isLeft, h) else defaultY(w)
        }
        if (!isLightBar) view.setupTouch(isLeft, params)
        wm.addView(view, params)
    }

    private fun updatePositions() {
        val (w, h) = dims()
        listOf(leftView to true, rightView to false).forEach { (view, isLeft) ->
            (view.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
                lp.width = w; lp.height = h
                lp.x = if (isLightBar) lightBarX(isLeft, w) else savedX(isLeft, w)
                lp.y = if (isLightBar) lightBarY(isLeft, h) else savedY(isLeft, w)
                try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
            }
        }
    }

    private fun dims(): Pair<Int, Int> = if (isLightBar) {
        val bar = (40 * context.resources.displayMetrics.density).toInt()
        if (isVertical) bar to barScreenH else barScreenW to bar
    } else {
        val s = config.size.dp.dpToPx(); s to s
    }

    /** 光带 X 位置：左右边缘模式贴左右边缘；上下边缘模式整排覆盖水平 */
    private fun lightBarX(isLeft: Boolean, w: Int): Int = when {
        isVertical -> if (isLeft) 0 else barScreenW - w
        else -> 0 // 横屏：覆盖整条
    }

    /**
     * 光带 Y 位置：左右边缘模式贴顶；上下边缘模式贴上下边。
     * Android 11+ 的 barScreen 已自动排除不透明系统栏，无需再手动减导航栏高度；
     * 低版本回退到物理尺寸 + 资源导航栏高度。
     */
    private fun lightBarY(isLeft: Boolean, h: Int): Int = when {
        isVertical -> 0
        else -> if (isLeft) 0 else {
            val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                barScreenH
            } else {
                (barScreenH - getNavBarHeight()).coerceAtLeast(0)
            }
            (bottom - h).coerceAtLeast(0)
        }
    }

    // ── Touch (仅图标模式) ───────────────────────────────

    private fun View.setupTouch(isLeft: Boolean, lp: WindowManager.LayoutParams) {
        val thisView = this
        val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                thisView.let { if (it is BsdIndicatorView) it.setLabelVisible(true) }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                thisView.let { if (it is BsdIndicatorView) it.setLabelVisible(false) }
                context.startActivity(Intent(context, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                thisView.let { if (it is BsdIndicatorView) it.setLabelVisible(false) }
                showPopupMenu(thisView, isLeft)
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val wlp = thisView.layoutParams as WindowManager.LayoutParams
                wlp.x = (wlp.x - dx.toInt()).coerceIn(-thisView.width / 2, screenW - thisView.width / 2)
                wlp.y = (wlp.y - dy.toInt()).coerceIn(getStatusBarHeight(), screenH - getNavBarHeight() - thisView.height)
                wm.updateViewLayout(thisView, wlp)
                return true
            }
        })
        setOnTouchListener { v, event ->
            gesture.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    (v as? BsdIndicatorView)?.setLabelVisible(false)
                    snapToEdge(v, isLeft); checkOverlap(); savePositionAsync(isLeft, v)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    (v as? BsdIndicatorView)?.setLabelVisible(false); true
                }
                else -> event.action == MotionEvent.ACTION_DOWN
            }
        }
    }

    private fun showPopupMenu(anchor: View, isLeft: Boolean) {
        val worst = maxOf(currentLeftLevel.ordinal, currentRightLevel.ordinal)
        val lc = when (AlertLevel.entries.getOrElse(worst) { AlertLevel.Safe }) {
            AlertLevel.Safe -> Color.parseColor("#9E9E9E")
            AlertLevel.Warning -> Color.parseColor("#FFC107")
            AlertLevel.Alert -> Color.parseColor("#FF9800")
            AlertLevel.Critical -> Color.parseColor("#F44336")
        }
        val tv = TextView(context).apply {
            text = "左:${currentLeftLevel.label}  右:${currentRightLevel.label}\n电量 ${currentBattery}%"
            setTextColor(lc); textSize = 14f; setPadding(20, 14, 20, 14)
            setBackgroundColor(Color.parseColor("#E0282828"))
        }
        val popup = PopupWindow(tv, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true; isFocusable = true
        }
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        // 先测量内容宽度，避免右侧弹窗超出屏幕左边缘
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popW = tv.measuredWidth.coerceAtLeast(1)
        val x = if (isLeft) {
            (loc[0] + anchor.width + 12).coerceAtMost(screenW - popW)
        } else {
            (loc[0] - popW - 12).coerceAtLeast(0)
        }
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY,
            x,
            loc[1] + anchor.height / 2 - 25)
    }

    private fun snapToEdge(view: View, isLeft: Boolean) {
        val wlp = view.layoutParams as WindowManager.LayoutParams
        val cx = wlp.x + view.width / 2
        wlp.x = if (isLeft) {
            if (cx < screenW / 2) 0.coerceAtLeast(edgeMargin) else (screenW - view.width - edgeMargin).coerceAtMost(screenW - view.width)
        } else {
            if (cx > screenW / 2) (screenW - view.width - edgeMargin).coerceAtLeast(0) else 0.coerceAtLeast(edgeMargin)
        }
        wm.updateViewLayout(view, wlp)
    }

    private fun checkOverlap() {
        val ll = leftView.layoutParams as WindowManager.LayoutParams
        val rl = rightView.layoutParams as WindowManager.LayoutParams
        // 阈值统一为像素：size(dpi 值) * 3 * density
        val t = config.size.dp * 3f * context.resources.displayMetrics.density
        val dx = abs((ll.x + leftView.width / 2) - (rl.x + rightView.width / 2))
        val dy = abs((ll.y + leftView.height / 2) - (rl.y + rightView.height / 2))
        if (sqrt((dx * dx + dy * dy).toDouble()) < t) {
            val s = config.size.dp.dpToPx()
            ll.x = defaultX(true, s); ll.y = defaultY(s)
            rl.x = defaultX(false, s); rl.y = defaultY(s)
            wm.updateViewLayout(leftView, ll); wm.updateViewLayout(rightView, rl)
            Toast.makeText(context, "图标已自动分开", Toast.LENGTH_SHORT).show()
        }
    }

    // ── helpers ────────────────────────────────────────────

    private val edgeMargin: Int get() = (16 * context.resources.displayMetrics.density).toInt()
    private fun defaultX(isLeft: Boolean, s: Int) = if (isLeft) edgeMargin else screenW - s - edgeMargin
    private fun defaultY(s: Int): Int {
        val sh = getStatusBarHeight(); val nh = getNavBarHeight()
        return sh + (screenH - sh - nh - s) / 2
    }
    private fun savedX(isLeft: Boolean, s: Int): Int =
        savedXValue(isLeft)?.coerceIn(0, screenW - s) ?: defaultX(isLeft, s)
    private fun savedY(isLeft: Boolean, s: Int): Int =
        savedYValue(isLeft)?.coerceIn(getStatusBarHeight(), screenH - getNavBarHeight() - s) ?: defaultY(s)

    /** 旋转后按保存时的屏幕尺寸比例换算 X，避免坐标错乱 */
    private fun savedXValue(isLeft: Boolean): Int? {
        val sx = (if (isLeft) savedLeftX else savedRightX) ?: return null
        val sw = if (isLeft) savedLeftW else savedRightW
        return if (sw > 0 && sw != screenW) (sx.toLong() * screenW / sw).toInt() else sx
    }

    /** 旋转后按保存时的屏幕尺寸比例换算 Y */
    private fun savedYValue(isLeft: Boolean): Int? {
        val sy = (if (isLeft) savedLeftY else savedRightY) ?: return null
        val sh = if (isLeft) savedLeftH else savedRightH
        return if (sh > 0 && sh != screenH) (sy.toLong() * screenH / sh).toInt() else sy
    }
    private fun savePositionAsync(isLeft: Boolean, view: View) {
        val lp = view.layoutParams as WindowManager.LayoutParams
        if (isLeft) { savedLeftX = lp.x; savedLeftY = lp.y }
        else { savedRightX = lp.x; savedRightY = lp.y }
        scope.launch {
            val side = if (isLeft) com.motobsd.data.overlay.BsdSide.Left else com.motobsd.data.overlay.BsdSide.Right
            overlayRepository.savePosition(side, lp.x, lp.y)
            overlayRepository.saveScreenDims(side, screenW, screenH)
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
    private fun getStatusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    private fun getNavBarHeight(): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    private fun Float.dpToPx(): Int = (this * context.resources.displayMetrics.density + 0.5f).toInt()
}
