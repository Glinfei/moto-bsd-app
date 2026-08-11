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

    private var testMode = false
    private var lastConnected = true

    fun updateAlert(left: AlertLevel, right: AlertLevel) {
        window?.setAlertLevel(BsdIndicatorView.Side.Left, left)
        window?.setAlertLevel(BsdIndicatorView.Side.Right, right)
    }

    fun updateBattery(pct: Int) {
        window?.setBattery(pct)
    }

    /** BLE 连接状态变化：断线显示灰色呼吸，重连恢复告警显示 */
    fun updateConnectionState(connected: Boolean) {
        lastConnected = connected
        if (!testMode) window?.setConnected(connected)
    }

    /** 设置页测试告警模式：不受连接状态影响，始终显示真实颜色 */
    fun setTestMode(enabled: Boolean) {
        testMode = enabled
        if (enabled) window?.setConnected(true)
        else window?.setConnected(lastConnected)
    }
}
