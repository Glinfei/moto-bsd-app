package com.motobsd.overlay

/**
 * 全局 OverlayWindow 持有者。
 *
 * OverlayService 创建窗口后经 [attach] 登记；BleService 直接调用
 * [updateThreat]/[updateConnectionState]，无需 Intent IPC。
 *
 * 这里同时缓存最近一次的连接状态与威胁度：窗口可能在数据早已到位之后才创建
 * （首次进入 App、服务被杀后 START_STICKY 重启、重新进入骑行），此时 StateFlow
 * 不会为新窗口重发旧值，灯带会停在威胁度 0。attach 时回放缓存即可补上这一段。
 */
object OverlayWindowHolder {
    var window: OverlayWindow? = null
        private set

    private var testMode = false
    private var lastConnected = true
    /** 最近一次真实 BLE 威胁度（0~1） */
    private var lastThreat: Pair<Float, Float> = 0f to 0f
    /** 最近一次测试告警威胁度；与真实值分开缓存，退出测试后不残留 */
    private var testThreat: Pair<Float, Float> = 0f to 0f

    /** OverlayService 创建窗口后登记，并把缓存状态立即回放给新窗口 */
    fun attach(window: OverlayWindow) {
        this.window = window
        window.setConnected(if (testMode) true else lastConnected)
        val (left, right) = if (testMode) testThreat else lastThreat
        window.setThreat(BsdIndicatorView.Side.Left, left)
        window.setThreat(BsdIndicatorView.Side.Right, right)
    }

    /** OverlayService 销毁窗口时注销；仅当仍是当前窗口才清除，避免误伤新窗口 */
    fun detach(window: OverlayWindow) {
        if (this.window === window) this.window = null
    }

    /** BLE 威胁度变化（0~1），测试模式时不覆盖测试值 */
    fun updateThreat(left: Float, right: Float) {
        lastThreat = left to right
        if (testMode) return
        window?.setThreat(BsdIndicatorView.Side.Left, left)
        window?.setThreat(BsdIndicatorView.Side.Right, right)
    }

    /** 测试模式直通（不受 testMode 拦截），由设置页测试告警调用 */
    fun setTestThreat(left: Float, right: Float) {
        testThreat = left to right
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
