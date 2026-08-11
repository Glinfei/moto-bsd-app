package com.motobsd.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.motobsd.data.ble.BleRepository
import com.motobsd.data.settings.SettingsRepository
import com.motobsd.model.AlertLevel
import com.motobsd.model.BleConnectionState
import com.motobsd.model.DeviceStatus
import com.motobsd.model.TargetObject
import com.motobsd.model.TargetRecord
import com.motobsd.service.OverlayService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val connectionState: BleConnectionState = BleConnectionState.Disconnected,
    val alertLeft: AlertLevel = AlertLevel.Safe,
    val alertRight: AlertLevel = AlertLevel.Safe,
    val deviceStatus: DeviceStatus = DeviceStatus(),
    val targets: List<TargetObject> = emptyList(),
    val rideMode: Boolean = false,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val bleRepository: BleRepository,
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        bleRepository.connectionState,
        bleRepository.alertState,
        bleRepository.deviceStatus,
        bleRepository.targets,
        settings.rideModeEnabled,
    ) { connectionState, alertState, deviceStatus, targets, rideMode ->
        DashboardUiState(
            connectionState = connectionState,
            alertLeft = alertState.first,
            alertRight = alertState.second,
            deviceStatus = deviceStatus,
            targets = targets,
            rideMode = rideMode,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    /** 上次连接设备（断线时用于一键重连） */
    val lastMac: StateFlow<String?> = bleRepository.lastMac
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前连接 RSSI（dBm，仅已连接时有值） */
    val rssi: StateFlow<Int?> = bleRepository.rssi
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 目标事件记录（以 obj_id 为单位） */
    val targetRecords: StateFlow<List<TargetRecord>> = bleRepository.targetRecords

    /** 最近一次收到雷达/BLE 数据的时间戳（用于显示"数据新鲜度"，防止静默失效） */
    private val _lastDataAt = MutableStateFlow(0L)
    val lastDataAt: StateFlow<Long> = _lastDataAt.asStateFlow()

    init {
        // 目标详情 / 有无目标 任一更新都刷新时间戳
        viewModelScope.launch {
            bleRepository.targets.collect { _lastDataAt.value = System.currentTimeMillis() }
        }
        viewModelScope.launch {
            bleRepository.alertState.collect { _lastDataAt.value = System.currentTimeMillis() }
        }
    }

    fun onScan() {
        // 跳转到设备列表页由 NavGraph 处理
    }

    fun onDisconnect() {
        // 手动断开时退出骑行模式，避免悬浮窗继续强制屏幕常亮
        viewModelScope.launch {
            if (settings.rideModeEnabled.first()) {
                settings.setRideModeEnabled(false)
                OverlayService.setRideMode(context, false)
            }
        }
        bleRepository.disconnect()
    }

    fun onHideToBackground() {
        // 由 Activity 处理：启动 OverlayService + moveTaskToBack
    }

    fun reconnectLast() {
        val mac = bleRepository.lastMac.value ?: return
        viewModelScope.launch {
            try {
                bleRepository.connect(mac)
            } catch (_: Exception) {
                // 无效 MAC 等异常：连接状态会回到 Disconnected，无需额外处理
            }
        }
    }

    /** 取消扫描/连接/重连：回到未连接状态，停止自动重试 */
    fun cancelConnect() {
        bleRepository.disconnect()
    }
}
