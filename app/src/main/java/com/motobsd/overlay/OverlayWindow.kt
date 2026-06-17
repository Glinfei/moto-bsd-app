package com.motobsd.overlay

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import com.motobsd.MainActivity
import com.motobsd.model.AlertLevel
import com.motobsd.model.OverlayConfig
import kotlin.math.abs

/**
 * 悬浮窗管理器。
 */
class OverlayWindow(private val context: Context) {

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val prefs: SharedPreferences =
        context.getSharedPreferences("overlay_pos", Context.MODE_PRIVATE)

    private val leftView  = BsdIndicatorView(context, BsdIndicatorView.Side.Left)
    private val rightView = BsdIndicatorView(context, BsdIndicatorView.Side.Right)

    private var isAttached = false
    private var screenW: Int = 0
    private var screenH: Int = 0
    private var config: OverlayConfig = OverlayConfig()

    // 当前告警状态（供长按菜单显示）
    private var currentLeftLevel: AlertLevel = AlertLevel.Safe
    private var currentRightLevel: AlertLevel = AlertLevel.Safe
    private var currentBattery: Int = 0

    // ── public API ────────────────────────────────────────

    fun show(config: OverlayConfig = OverlayConfig()) {
        this.config = config
        if (isAttached) { updatePositions(); return }
        updateScreenSize()
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
            BsdIndicatorView.Side.Left  -> { currentLeftLevel = level; leftView.setAlertLevel(level) }
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

    fun onConfigurationChanged(newConfig: Configuration) {
        updateScreenSize()
        updatePositions()
    }

    // ── WindowManager ─────────────────────────────────────

    private fun addView(view: View, isLeft: Boolean) {
        val size = config.size.dp.dpToPx()
        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = defaultX(isLeft)
            y = defaultY()
        }
        view.setupTouch(isLeft, params)
        wm.addView(view, params)
    }

    private fun updatePositions() {
        val size = config.size.dp.dpToPx()
        listOf(Triple(leftView, true, size), Triple(rightView, false, size)).forEach { (view, isLeft, px) ->
            (view.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
                lp.width = px; lp.height = px
                lp.x = savedX(isLeft, px)
                lp.y = savedY(isLeft, px)
                try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
            }
        }
    }

    // ── touch ─────────────────────────────────────────────

    private fun View.setupTouch(isLeft: Boolean, lp: WindowManager.LayoutParams) {
        val thisView = this

        val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                thisView.let { if (it is BsdIndicatorView) it.setLabelVisible(true) }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                thisView.let { if (it is BsdIndicatorView) it.setLabelVisible(false) }
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                thisView.let { if (it is BsdIndicatorView) it.setLabelVisible(false) }
                showPopupMenu(thisView, isLeft)
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val wlp = thisView.layoutParams as WindowManager.LayoutParams
                wlp.x = (wlp.x - dx.toInt()).coerceIn(-thisView.width / 2, screenW - thisView.width / 2)
                wlp.y = (wlp.y - dy.toInt()).coerceIn(
                    getStatusBarHeight(),
                    screenH - getNavBarHeight() - thisView.height
                )
                wm.updateViewLayout(thisView, wlp)
                return true
            }
        })

        setOnTouchListener { v, event ->
            gesture.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    (v as? BsdIndicatorView)?.setLabelVisible(false)
                    snapToEdge(v, isLeft)
                    checkOverlap()
                    savePosition(isLeft, v)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    (v as? BsdIndicatorView)?.setLabelVisible(false)
                    true
                }
                else -> event.action == MotionEvent.ACTION_DOWN
            }
        }
    }

    // ── popup menu ────────────────────────────────────────

    private fun showPopupMenu(anchor: View, isLeft: Boolean) {
        val sideLabel = if (isLeft) "左" else "右"
        val sideLevel = if (isLeft) currentLeftLevel else currentRightLevel
        val levelColor = when (sideLevel) {
            AlertLevel.Safe -> Color.parseColor("#9E9E9E")
            AlertLevel.Warning -> Color.parseColor("#FFC107")
            AlertLevel.Alert -> Color.parseColor("#FF9800")
            AlertLevel.Critical -> Color.parseColor("#F44336")
        }

        val text = "$sideLabel · ${sideLevel.label} · ${currentBattery}%"

        val textView = TextView(context).apply {
            this.text = text
            setTextColor(levelColor)
            textSize = 14f
            setPadding(20, 14, 20, 14)
            setBackgroundColor(Color.parseColor("#E0282828"))
        }

        val popup = PopupWindow(
            textView,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isFocusable = true
        }

        // 位置：圆点旁边
        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val popupX = if (isLeft) anchorLoc[0] + anchor.width + 12
                     else anchorLoc[0] - 12
        val popupY = anchorLoc[1] + anchor.height / 2 - 25

        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, popupX, popupY)
    }

    // ── snap & overlap ────────────────────────────────────

    private fun snapToEdge(view: View, isLeft: Boolean) {
        val wlp = view.layoutParams as WindowManager.LayoutParams
        val cx = wlp.x + view.width / 2
        wlp.x = if (isLeft) {
            if (cx < screenW / 2) 0.coerceAtLeast(edgeMargin)
            else (screenW - view.width - edgeMargin).coerceAtMost(screenW - view.width)
        } else {
            if (cx > screenW / 2) (screenW - view.width - edgeMargin).coerceAtLeast(0)
            else 0.coerceAtLeast(edgeMargin)
        }
        wm.updateViewLayout(view, wlp)
    }

    private fun checkOverlap() {
        val leftLp = leftView.layoutParams as WindowManager.LayoutParams
        val rightLp = rightView.layoutParams as WindowManager.LayoutParams
        val threshold = config.size.dp * 3f
        val dx = abs((leftLp.x + leftView.width / 2) - (rightLp.x + rightView.width / 2))
        val dy = abs((leftLp.y + leftView.height / 2) - (rightLp.y + rightView.height / 2))
        val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
        if (dist < threshold) {
            leftLp.x = defaultX(isLeft = true); leftLp.y = defaultY()
            rightLp.x = defaultX(isLeft = false); rightLp.y = defaultY()
            wm.updateViewLayout(leftView, leftLp)
            wm.updateViewLayout(rightView, rightLp)
            Toast.makeText(context, "图标已自动分开", Toast.LENGTH_SHORT).show()
        }
    }

    // ── position helpers ──────────────────────────────────

    private val edgeMargin: Int get() = (16 * context.resources.displayMetrics.density).toInt()
    private val dotSize: Int get() = config.size.dp.dpToPx()

    private fun defaultX(isLeft: Boolean): Int {
        return if (isLeft) edgeMargin else screenW - dotSize - edgeMargin
    }

    private fun defaultY(): Int {
        val statusBarH = getStatusBarHeight()
        val navBarH = getNavBarHeight()
        val usableH = screenH - statusBarH - navBarH
        return statusBarH + (usableH - dotSize) / 2
    }

    private fun savedX(isLeft: Boolean, sizePx: Int): Int {
        val keyX = if (isLeft) "left_x" else "right_x"
        return prefs.getInt(keyX, defaultX(isLeft)).coerceIn(0, screenW - sizePx)
    }

    private fun savedY(isLeft: Boolean, sizePx: Int): Int {
        val keyY = if (isLeft) "left_y" else "right_y"
        return prefs.getInt(keyY, defaultY()).coerceIn(getStatusBarHeight(), screenH - getNavBarHeight() - sizePx)
    }

    private fun savePosition(isLeft: Boolean, view: View) {
        val lp = view.layoutParams as WindowManager.LayoutParams
        prefs.edit().apply {
            if (isLeft) { putInt("left_x", lp.x); putInt("left_y", lp.y) }
            else { putInt("right_x", lp.x); putInt("right_y", lp.y) }
        }.apply()
    }

    private fun updateScreenSize() {
        val metrics = context.resources.displayMetrics
        screenW = metrics.widthPixels; screenH = metrics.heightPixels
    }

    private fun getStatusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun getNavBarHeight(): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun Float.dpToPx(): Int =
        (this * context.resources.displayMetrics.density + 0.5f).toInt()
}
