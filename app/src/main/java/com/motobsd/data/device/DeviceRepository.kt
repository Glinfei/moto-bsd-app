package com.motobsd.data.device

import android.bluetooth.BluetoothGatt
import com.motobsd.ble.Protocol
import com.motobsd.model.DeviceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.callback.DataReceivedCallback
import java.util.UUID

/**
 * 读取 DIS (Device Information Service) 信息。
 */
class DeviceRepository(
    private val bleManagerProvider: () -> BleManager?,
) {
    private val _disInfo = MutableStateFlow<Map<UUID, String>>(emptyMap())
    val disInfo: StateFlow<Map<UUID, String>> = _disInfo.asStateFlow()

    fun readDisInfo() {
        val bleManager = bleManagerProvider() ?: return
        val chars = listOf(
            Protocol.DIS_MANUFACTURER_NAME,
            Protocol.DIS_MODEL_NUMBER,
            Protocol.DIS_SERIAL_NUMBER,
            Protocol.DIS_HARDWARE_REVISION,
            Protocol.DIS_FIRMWARE_REVISION,
        )

        // 注意: DIS 读取通过 BleManager 内部的 GATT 完成
        // DeviceRepository 是薄层，由 BleConnectionManager 调用其 readDisFromGatt()
    }

    fun updateDisValue(uuid: UUID, value: String) {
        val map = _disInfo.value.toMutableMap()
        map[uuid] = value
        _disInfo.value = map
    }

    fun clear() {
        _disInfo.value = emptyMap()
    }
}
