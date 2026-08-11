package com.motobsd.data.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import com.motobsd.ble.Protocol
import com.motobsd.model.AlertLevel
import com.motobsd.model.BleConnectionState
import com.motobsd.model.DeviceStatus
import com.motobsd.model.TargetObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import java.util.UUID

/**
 * MotoBSD BLE 连接管理器。
 *
 * 职责：
 * - 封装 Nordic BleManager 的 GATT 操作
 * - 通过回调上报事件给 [BleRepositoryImpl]
 * - 内部维护自己的 _deviceStatus（用于从 BLE 通知更新）
 *
 * 不再直接暴露状态给全局单例，所有状态由 BleRepositoryImpl 统一管理。
 */
class BleConnectionManager(context: Context) : BleManager(context) {

    // ── GATT Characteristic 缓存 ──────────────────────────

    private var alertStatusChar: BluetoothGattCharacteristic? = null
    private var targetDetailsChar: BluetoothGattCharacteristic? = null
    private var deviceStatusChar: BluetoothGattCharacteristic? = null
    private var radarPowerChar: BluetoothGattCharacteristic? = null
    private var dfuTriggerChar: BluetoothGattCharacteristic? = null
    private var systemResetChar: BluetoothGattCharacteristic? = null
    private var deviceNameChar: BluetoothGattCharacteristic? = null
    private var batteryLevelChar: BluetoothGattCharacteristic? = null

    // DIS characteristics
    private var disChars = mutableMapOf<UUID, BluetoothGattCharacteristic?>()

    // ── 内部状态 ──────────────────────────────────────────

    private val _deviceStatus = MutableStateFlow(DeviceStatus())
    val deviceStatus: StateFlow<DeviceStatus> = _deviceStatus.asStateFlow()

    // ── 回调（由 BleRepositoryImpl 设置） ──────────────────

    /** GATT 连接就绪（服务发现 + 通知订阅完成） */
    var onReady: (() -> Unit)? = null

    /** GATT 断开连接 */
    var onDisconnected: (() -> Unit)? = null

    /** services invalidated */
    var onServicesInvalidated: (() -> Unit)? = null

    /** alert_status 变化：左右"有无目标"（0/1），告警等级由仓库层基于目标计算 */
    var onAlertChanged: ((Boolean, Boolean) -> Unit)? = null

    /** 目标列表更新 */
    var onTargetDetails: ((List<TargetObject>) -> Unit)? = null

    /** DIS 信息读取完成 */
    var onDisInfoRead: ((UUID, String) -> Unit)? = null

    /** 设备状态更新（来自 notify） */
    var onDeviceStatusChanged: ((DeviceStatus) -> Unit)? = null

    /** 设备名称读取完成 (write 后回读验证也走这个) */
    var onDeviceNameRead: ((String) -> Unit)? = null

    // ── 公开操作 ──────────────────────────────────────────

    fun connectTo(device: BluetoothDevice) {
        connect(device)
            .useAutoConnect(false)
            .enqueue()
    }

    /** 读取一次连接 RSSI（成功后回调，失败忽略，下次轮询再试） */
    fun readRssi(onResult: (Int) -> Unit) {
        readRssi()
            .with(no.nordicsemi.android.ble.callback.RssiCallback { _, rssi -> onResult(rssi) })
            .enqueue()
    }

    fun writeRadarPower(on: Boolean) {
        radarPowerChar?.let { c ->
            writeCharacteristic(c, byteArrayOf(if (on) 0x01 else 0x00)).enqueue()
        }
    }

    fun triggerDfu(mode: Int = 0x01, onDone: ((Boolean) -> Unit)? = null) {
        val c = dfuTriggerChar
        if (c == null) {
            onDone?.invoke(false)
            return
        }
        val request = writeCharacteristic(c, byteArrayOf(mode.toByte()))
        if (onDone == null) {
            request.enqueue()
        } else {
            request.done { _ -> onDone(true) }
                .fail { _, _ -> onDone(false) }
                .enqueue()
        }
    }

    fun writeSystemReset() {
        systemResetChar?.let { c ->
            writeCharacteristic(c, byteArrayOf(0x01)).enqueue()
        }
    }

    /** 读取设备名称并挂起直到完成（成功返回名称，失败返回 null）；结果同步到 [onDeviceNameRead] */
    suspend fun readDeviceNameResult(): String? = suspendCancellableCoroutine { cont ->
        val c = deviceNameChar
        if (c == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        readCharacteristic(c)
            .with(no.nordicsemi.android.ble.callback.DataReceivedCallback { _, data ->
                val name = data.getStringValue(0) ?: ""
                onDeviceNameRead?.invoke(name)
                if (cont.isActive) cont.resume(name)
            })
            .fail { _, _ -> if (cont.isActive) cont.resume(null) }
            .enqueue()
    }

    /** 写入设备名称（UTF-8，最长 20 字节） */
    fun writeDeviceName(name: String) {
        deviceNameChar?.let { c ->
            val bytes = name.toByteArray(Charsets.UTF_8).take(20).toByteArray()
            writeCharacteristic(c, bytes).enqueue()
        }
    }

    // ── BleManagerGattCallback ────────────────────────────

    override fun getGattCallback(): BleManagerGattCallback {
        return object : BleManagerGattCallback() {

            override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
                val service = gatt.getService(Protocol.SERVICE_UUID) ?: return false
                alertStatusChar = service.getCharacteristic(Protocol.CHARACTERISTIC_ALERT_STATUS)
                targetDetailsChar = service.getCharacteristic(Protocol.CHARACTERISTIC_TARGET_DETAILS)
                deviceStatusChar = service.getCharacteristic(Protocol.CHARACTERISTIC_DEVICE_STATUS)
                radarPowerChar = service.getCharacteristic(Protocol.CHARACTERISTIC_RADAR_POWER)
                dfuTriggerChar = service.getCharacteristic(Protocol.CHARACTERISTIC_DFU_TRIGGER)
                systemResetChar = service.getCharacteristic(Protocol.CHARACTERISTIC_SYSTEM_RESET)
                deviceNameChar = service.getCharacteristic(Protocol.CHARACTERISTIC_DEVICE_NAME)

                val disService = gatt.getService(Protocol.DIS_SERVICE_UUID)
                if (disService != null) {
                    disChars[Protocol.DIS_MANUFACTURER_NAME] =
                        disService.getCharacteristic(Protocol.DIS_MANUFACTURER_NAME)
                    disChars[Protocol.DIS_MODEL_NUMBER] =
                        disService.getCharacteristic(Protocol.DIS_MODEL_NUMBER)
                    disChars[Protocol.DIS_SERIAL_NUMBER] =
                        disService.getCharacteristic(Protocol.DIS_SERIAL_NUMBER)
                    disChars[Protocol.DIS_HARDWARE_REVISION] =
                        disService.getCharacteristic(Protocol.DIS_HARDWARE_REVISION)
                    disChars[Protocol.DIS_FIRMWARE_REVISION] =
                        disService.getCharacteristic(Protocol.DIS_FIRMWARE_REVISION)
                }

                val basService = gatt.getService(Protocol.BAS_SERVICE_UUID)
                if (basService != null) {
                    batteryLevelChar = basService.getCharacteristic(Protocol.BAS_BATTERY_LEVEL)
                }
                return true
            }

            override fun onServicesInvalidated() {
                clearCaches()
                onServicesInvalidated?.invoke()
            }

            override fun initialize() {
                // 设置通知回调
                alertStatusChar?.let {
                    setNotificationCallback(it).with(::onAlertData)
                }
                targetDetailsChar?.let {
                    setNotificationCallback(it).with(::onTargetData)
                }
                deviceStatusChar?.let {
                    setNotificationCallback(it).with(::onDeviceStatusData)
                }

                // 启用通知
                alertStatusChar?.let { enableNotifications(it).enqueue() }
                targetDetailsChar?.let { enableNotifications(it).enqueue() }
                deviceStatusChar?.let { enableNotifications(it).enqueue() }

                // 主动读取初始值（协议为 read + notify，不能只依赖订阅后推送）
                alertStatusChar?.let { c ->
                    readCharacteristic(c).with(
                        no.nordicsemi.android.ble.callback.DataReceivedCallback { _, data ->
                            val (l, r) = Protocol.parseAlertStatus(data.value)
                            onAlertChanged?.invoke(l, r)
                        }
                    ).enqueue()
                }
                deviceStatusChar?.let { c ->
                    readCharacteristic(c).with(
                        no.nordicsemi.android.ble.callback.DataReceivedCallback { _, data ->
                            val status = Protocol.parseDeviceStatus(data.value)
                            _deviceStatus.value = status
                            onDeviceStatusChanged?.invoke(status)
                        }
                    ).enqueue()
                }

                // 读取 DIS 信息
                readDisCharacters()

                // 读取 BAS battery level + 订阅
                batteryLevelChar?.let { c ->
                    setNotificationCallback(c).with { _, data -> updateBatteryFromBas(data) }
                    enableNotifications(c).enqueue()
                    readCharacteristic(c).with(
                        no.nordicsemi.android.ble.callback.DataReceivedCallback { _, data ->
                            updateBatteryFromBas(data)
                        }
                    ).enqueue()
                }

                onReady?.invoke()
            }
        }
    }

    // ── 通知数据处理 ──────────────────────────────────────

    private fun onAlertData(device: BluetoothDevice, data: Data) {
        val (left, right) = Protocol.parseAlertStatus(data.value)
        onAlertChanged?.invoke(left, right)
    }

    private fun onTargetData(device: BluetoothDevice, data: Data) {
        val list = Protocol.parseTargetDetails(data.value)
        onTargetDetails?.invoke(list)
    }

    private fun onDeviceStatusData(device: BluetoothDevice, data: Data) {
        val status = Protocol.parseDeviceStatus(data.value)
        _deviceStatus.value = status
        onDeviceStatusChanged?.invoke(status)
    }

    /** BAS 2A19 电量百分比：BAS 优先于 device_status 的线性换算，同步到仓库 */
    private fun updateBatteryFromBas(data: Data) {
        val pct = data.value?.get(0)?.toInt()?.and(0xFF) ?: return
        val updated = _deviceStatus.value.copy(batteryPercent = pct)
        _deviceStatus.value = updated
        onDeviceStatusChanged?.invoke(updated)
    }

    // ── DIS ───────────────────────────────────────────────

    private fun readDisCharacters() {
        disChars.forEach { (uuid, characteristic) ->
            characteristic ?: return@forEach
            readCharacteristic(characteristic)
                .with(no.nordicsemi.android.ble.callback.DataReceivedCallback { _, data ->
                    val value = data.getStringValue(0) ?: "--"
                    onDisInfoRead?.invoke(uuid, value)
                })
                .enqueue()
        }
    }

    private fun clearCaches() {
        alertStatusChar = null
        targetDetailsChar = null
        deviceStatusChar = null
        radarPowerChar = null
        dfuTriggerChar = null
        systemResetChar = null
        deviceNameChar = null
        batteryLevelChar = null
        disChars.clear()
    }
}
