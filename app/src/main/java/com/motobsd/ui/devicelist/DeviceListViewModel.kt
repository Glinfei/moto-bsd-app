package com.motobsd.ui.devicelist

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.motobsd.data.ble.BleRepository
import com.motobsd.model.BleConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeviceListUiState(
    /** 是否正在扫描 */
    val scanning: Boolean = false,
    /** 发现的设备列表（按信号强度排序） */
    val devices: List<ScanResult> = emptyList(),
    /** 名称过滤关键词（为空则显示全部） */
    val nameFilter: String = "",
    /** 正在连接的 MAC */
    val connectingMac: String? = null,
    /** 连接状态（用于判断连接结果） */
    val connectionState: BleConnectionState = BleConnectionState.Disconnected,
    /** 错误/提示消息 */
    val message: String? = null,
)

@HiltViewModel
class DeviceListViewModel @Inject constructor(
    private val bleRepository: BleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceListUiState())
    val uiState: StateFlow<DeviceListUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    init {
        // 监听连接状态变化，更新 connectingMac 和 message
        viewModelScope.launch {
            bleRepository.connectionState.collect { state ->
                val current = _uiState.value
                when (state) {
                    is BleConnectionState.Ready -> {
                        // 连接成功 → 清除转圈
                        if (current.connectingMac != null) {
                            _uiState.value = current.copy(
                                connectingMac = null,
                                scanning = false,
                                message = null,
                            )
                        }
                    }
                    is BleConnectionState.Error -> {
                        // 连接失败 → 清除转圈，显示错误
                        if (current.connectingMac != null) {
                            _uiState.value = current.copy(
                                connectingMac = null,
                                message = state.message,
                            )
                        }
                    }
                    is BleConnectionState.Disconnected -> {
                        // 如果扫描中时连接被断开，清理状态
                        if (current.connectingMac != null && current.connectionState !is BleConnectionState.Ready) {
                            _uiState.value = current.copy(
                                connectingMac = null,
                                message = "连接失败，请重试",
                            )
                        }
                    }
                    else -> {}
                }
                _uiState.value = _uiState.value.copy(connectionState = state)
            }
        }
    }

    fun startScan() {
        scanJob?.cancel()
        _uiState.value = _uiState.value.copy(
            scanning = true,
            devices = emptyList(),
            message = null,
            connectingMac = null,
        )

        scanJob = viewModelScope.launch {
            try {
                bleRepository.scan().collect { devices ->
                    _uiState.value = _uiState.value.copy(devices = devices)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(message = e.message ?: "扫描失败")
            } finally {
                _uiState.value = _uiState.value.copy(scanning = false)
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _uiState.value = _uiState.value.copy(scanning = false)
    }

    fun toggleScan() {
        if (_uiState.value.scanning) stopScan() else startScan()
    }

    fun setFilter(filter: String) {
        _uiState.value = _uiState.value.copy(nameFilter = filter)
    }

    /** 返回过滤后的设备列表，MotoBSD 设备置顶 */
    fun getFilteredDevices(): List<ScanResult> {
        val state = _uiState.value
        val all = state.devices
        val filter = state.nameFilter.trim()

        val filtered = if (filter.isEmpty()) {
            all
        } else {
            all.filter { result ->
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                name.contains(filter, ignoreCase = true)
            }
        }

        // MotoBSD 设备置顶，同组内按信号强度排序
        return filtered.sortedWith(
            compareByDescending<ScanResult> { result ->
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                name.contains("MotoBSD", ignoreCase = true) || name.contains("BSD", ignoreCase = true)
            }.thenByDescending { it.rssi }
        )
    }

    fun selectDevice(mac: String) {
        _uiState.value = _uiState.value.copy(connectingMac = mac, message = null)
        viewModelScope.launch {
            try {
                bleRepository.connect(mac)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    connectingMac = null,
                    message = e.message ?: "连接失败",
                )
            }
        }
    }

    /** 判断蓝牙是否开启 */
    fun isBluetoothEnabled(adapter: BluetoothAdapter?): Boolean {
        return adapter?.isEnabled == true
    }

    fun onClearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
    }
}
