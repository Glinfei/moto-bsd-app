package com.motobsd.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局单例 — BLE 状态共享，供 BleService 写入、MainActivity UI 读取。
 */
object BleStateHolder {
    private val _connectionState = MutableStateFlow(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _alertLeft = MutableStateFlow(AlertLevel.Safe)
    val alertLeft: StateFlow<AlertLevel> = _alertLeft.asStateFlow()
    private val _alertRight = MutableStateFlow(AlertLevel.Safe)
    val alertRight: StateFlow<AlertLevel> = _alertRight.asStateFlow()

    private val _deviceStatus = MutableStateFlow(DeviceStatus())
    val deviceStatus: StateFlow<DeviceStatus> = _deviceStatus.asStateFlow()

    private val _disInfo = MutableStateFlow<Map<java.util.UUID, String>>(emptyMap())
    val disInfo: StateFlow<Map<java.util.UUID, String>> = _disInfo.asStateFlow()

    fun updateConnectionState(state: ConnectionState) { _connectionState.value = state }
    fun updateAlert(left: AlertLevel, right: AlertLevel) {
        _alertLeft.value = left; _alertRight.value = right
    }
    fun updateDeviceStatus(status: DeviceStatus) { _deviceStatus.value = status }
    fun updateDisInfo(info: Map<java.util.UUID, String>) { _disInfo.value = info }
}
