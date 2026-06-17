package com.motobsd.overlay

import com.motobsd.model.AlertLevel

/**
 * 全局 OverlayWindow 持有者。
 *
 * OverlayService 创建窗口后注入此处；
 * BleService 直接调用 updateAlert/updateBattery，无需 Intent IPC。
 */
object OverlayWindowHolder {
    var window: OverlayWindow? = null

    fun updateAlert(left: AlertLevel, right: AlertLevel) {
        window?.setAlertLevel(BsdIndicatorView.Side.Left, left)
        window?.setAlertLevel(BsdIndicatorView.Side.Right, right)
    }

    fun updateBattery(pct: Int) {
        window?.setBattery(pct)
    }
}
