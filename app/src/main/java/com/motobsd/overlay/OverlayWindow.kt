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
    private var config: OverlayConfig = OverlayConfig()

    private var savedLeftX: Int? = null
    private var savedLeftY: Int? = null
    private var savedRightX: Int? = null
    private var savedRightY: Int? = null

    private var currentLeftLevel: AlertLevel = AlertLevel.Safe
    private var currentRightLevel: AlertLevel = AlertLevel.Safe
    private var currentBattery: Int = 0

    // ── public API ────────────────────────────────────────

    fun show(config: OverlayConfig = OverlayConfig()) {
        this.config = config
        updateScreenSize()
        scope.launch {
            val (lx, ly) = overlayRepository.loadPosition(com.motobsd.data.overlay.BsdSide.Left)
            val (rx, ry) = overlayRepository.loadPosition(com.motobsd.data.overlay.BsdSide.Right)
            savedLeftX = lx; savedLeftY = ly
            savedRightX = rx; savedRightY = ry
            doShow()
        }
    }

    private fun doShow() {
        if (isAttached) { updatePositions(); return }
        addView(leftView, isLeft = true)
        addView(rightView, isLeft = false)
        isAttached = true
        leftView.applyConfig(config)
        rightView.applyConfig(config)
        updatePositions()
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

    fun applyConfig(config: OverlayConfig) {
        this.config = config
        leftView.applyConfig(config)
        rightView.applyConfig(config)
        updatePositions()
    }

    /** 重读屏幕物理尺寸并更新光带位置。用户点「切换横竖屏」触发。 */
    fun refresh() {
        updateScreenSize()
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
        val bar = (60 * context.resources.displayMetrics.density).toInt()
        if (isVertical) bar to screenH else screenW to bar
    } else {
        val s = config.size.dp.dpToPx(); s to s
    }

    /** 光带 X 位置：竖屏贴左右边缘；横屏整排覆盖水平 */
    private fun lightBarX(isLeft: Boolean, w: Int): Int = when {
        isVertical -> if (isLeft) 0 else screenW - w
        else -> 0 // 横屏：覆盖整条
    }

    /** 光带 Y 位置：竖屏贴顶；横屏贴上下边缘 */
    private fun lightBarY(isLeft: Boolean, h: Int): Int = when {
        isVertical -> 0
        else -> if (isLeft) 0 else screenH - h
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
        val sideLabel = if (isLeft) "左" else "右"
        val sideLevel = if (isLeft) currentLeftLevel else currentRightLevel
        val lc = when (sideLevel) {
            AlertLevel.Safe -> Color.parseColor("#9E9E9E")
            AlertLevel.Warning -> Color.parseColor("#FFC107")
            AlertLevel.Alert -> Color.parseColor("#FF9800")
            AlertLevel.Critical -> Color.parseColor("#F44336")
        }
        val tv = TextView(context).apply {
            text = "$sideLabel · ${sideLevel.label} · ${currentBattery}%"
            setTextColor(lc); textSize = 14f; setPadding(20, 14, 20, 14)
            setBackgroundColor(Color.parseColor("#E0282828"))
        }
        val popup = PopupWindow(tv, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true; isFocusable = true
        }
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY,
            if (isLeft) loc[0] + anchor.width + 12 else loc[0] - 12,
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
        val t = config.size.dp * 3f
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
        (if (isLeft) savedLeftX else savedRightX)?.coerceIn(0, screenW - s) ?: defaultX(isLeft, s)
    private fun savedY(isLeft: Boolean, s: Int): Int =
        (if (isLeft) savedLeftY else savedRightY)?.coerceIn(getStatusBarHeight(), screenH - getNavBarHeight() - s) ?: defaultY(s)
    private fun savePositionAsync(isLeft: Boolean, view: View) {
        val lp = view.layoutParams as WindowManager.LayoutParams
        if (isLeft) { savedLeftX = lp.x; savedLeftY = lp.y }
        else { savedRightX = lp.x; savedRightY = lp.y }
        scope.launch {
            val side = if (isLeft) com.motobsd.data.overlay.BsdSide.Left else com.motobsd.data.overlay.BsdSide.Right
            overlayRepository.savePosition(side, lp.x, lp.y)
        }
    }
    private fun updateScreenSize() {
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
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
