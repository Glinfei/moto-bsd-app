package com.motobsd.overlay

/**
 * 全局 OverlayWindow 持有者。
 *
 * OverlayService 创建窗口后注入此处；
 * BleService 直接调用 updateThreat/updateConnectionState，无需 Intent IPC。
 */
object OverlayWindowHolder {
    var window: OverlayWindow? = null

    private var testMode = false
    private var lastConnected = true

    /** BLE 威胁度变化（0~1），测试模式时不覆盖测试值 */
    fun updateThreat(left: Float, right: Float) {
        if (testMode) return
        window?.setThreat(BsdIndicatorView.Side.Left, left)
        window?.setThreat(BsdIndicatorView.Side.Right, right)
    }

    /** 测试模式直通（不受 testMode 拦截），由设置页测试告警调用 */
    fun setTestThreat(left: Float, right: Float) {
        window?.setThreat(BsdIndicatorView.Side.Left, left)
        window?.setThreat(BsdIndicatorView.Side.Right, right)
    }

    /** BLE 连接状态变化：断线显示灰色呼吸，重连恢复威胁度显示 */
    fun updateConnectionState(connected: Boolean) {
        lastConnected = connected
        if (!testMode) window?.setConnected(connected)
    }

    /** 设置页测试告警模式：不受连接状态影响，始终显示测试颜色 */
    fun setTestMode(enabled: Boolean) {
        testMode = enabled
        if (enabled) window?.setConnected(true)
        else window?.setConnected(lastConnected)
    }
}
