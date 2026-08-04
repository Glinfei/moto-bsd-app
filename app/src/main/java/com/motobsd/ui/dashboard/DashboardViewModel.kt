package com.motobsd.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.motobsd.data.ble.BleRepository
import com.motobsd.model.AlertLevel
import com.motobsd.model.BleConnectionState
import com.motobsd.model.DeviceStatus
import com.motobsd.model.TargetObject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DashboardUiState(
    val connectionState: BleConnectionState = BleConnectionState.Disconnected,
    val alertLeft: AlertLevel = AlertLevel.Safe,
    val alertRight: AlertLevel = AlertLevel.Safe,
    val deviceStatus: DeviceStatus = DeviceStatus(),
    val targets: List<TargetObject> = emptyList(),
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val bleRepository: BleRepository,
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        bleRepository.connectionState,
        bleRepository.alertState,
        bleRepository.deviceStatus,
        bleRepository.targets,
    ) { connectionState, alertState, deviceStatus, targets ->
        DashboardUiState(
            connectionState = connectionState,
            alertLeft = alertState.first,
            alertRight = alertState.second,
            deviceStatus = deviceStatus,
            targets = targets,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    fun onScan() {
        // 跳转到设备列表页由 NavGraph 处理
    }

    fun onDisconnect() {
        bleRepository.disconnect()
    }

    fun onHideToBackground() {
        // 由 Activity 处理：启动 OverlayService + moveTaskToBack
    }
}
