package com.motobsd.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.motobsd.data.ble.BleRepository
import com.motobsd.model.DeviceStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

data class DisInfo(
    val manufacturer: String = "--",
    val model: String = "--",
    val serial: String = "--",
    val hardwareRev: String = "--",
    val firmwareRev: String = "--",
)

private val DIS_UUIDS = mapOf(
    "manufacturer" to UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb"),
    "model" to UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb"),
    "serial" to UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb"),
    "hardwareRev" to UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb"),
    "firmwareRev" to UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb"),
)

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val bleRepository: BleRepository,
) : ViewModel() {

    val deviceStatus: StateFlow<DeviceStatus> = bleRepository.deviceStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceStatus())

    val deviceName: StateFlow<String> = bleRepository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _disInfo = MutableStateFlow(DisInfo())
    val disInfo: StateFlow<DisInfo> = _disInfo.asStateFlow()

    private val _nameMessage = MutableStateFlow<String?>(null)
    val nameMessage: StateFlow<String?> = _nameMessage.asStateFlow()

    init {
        viewModelScope.launch {
            bleRepository.disInfo.collect { info ->
                _disInfo.value = DisInfo(
                    manufacturer = info[DIS_UUIDS["manufacturer"]] ?: "--",
                    model = info[DIS_UUIDS["model"]] ?: "--",
                    serial = info[DIS_UUIDS["serial"]] ?: "--",
                    hardwareRev = info[DIS_UUIDS["hardwareRev"]] ?: "--",
                    firmwareRev = info[DIS_UUIDS["firmwareRev"]] ?: "--",
                )
            }
        }
    }

    fun onRadarToggle(on: Boolean) {
        bleRepository.setRadarPower(on)
    }

    fun onSystemReset() {
        bleRepository.systemReset()
    }

    fun onRefreshName() {
        _nameMessage.value = "读取中..."
        viewModelScope.launch {
            val name = withTimeoutOrNull(4_000) { bleRepository.readDeviceName() }
            _nameMessage.value = when {
                name.isNullOrEmpty() -> "读取失败，请重试"
                else -> "已刷新：$name"
            }
        }
    }

    fun onSaveName(newName: String) {
        val trimmed = newName.trim().take(20)
        if (trimmed.isEmpty()) {
            _nameMessage.value = "名称不能为空"
            return
        }
        _nameMessage.value = "写入中..."
        // 回读验证
        viewModelScope.launch {
            bleRepository.writeDeviceName(trimmed)
            kotlinx.coroutines.delay(500)
            val verified = withTimeoutOrNull(4_000) { bleRepository.readDeviceName() }
            kotlinx.coroutines.delay(500)
            _nameMessage.value = if (verified == trimmed) {
                "名称已更新为: $trimmed（重连后生效）"
            } else {
                "名称已写入，回读不一致（重连后生效）"
            }
        }
    }

    fun onClearNameMessage() {
        _nameMessage.value = null
    }
}
