package com.motobsd.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.motobsd.model.BleStateHolder
import com.motobsd.model.ConnectionState
import com.motobsd.model.DeviceStatus
import com.motobsd.model.TargetObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import java.util.UUID

/**
 * 基于 Nordic Android-BLE-Library (2.7.0) 的 MotoBSD BLE 管理器。
 *
 * GATT characteristics 在 isRequiredServiceSupported() 中缓存，
 * initialize() 中使用缓存的引用操作 GATT。
 */
class MotoBsdBleManager(context: Context) : BleManager(context) {

    // ── 缓存 GATT characteristics ─────────────────────────
    private var alertStatusChar: BluetoothGattCharacteristic? = null
    private var targetDetailsChar: BluetoothGattCharacteristic? = null
    private var deviceStatusChar: BluetoothGattCharacteristic? = null
    private var radarPowerChar: BluetoothGattCharacteristic? = null
    private var dfuTriggerChar: BluetoothGattCharacteristic? = null
    private var systemResetChar: BluetoothGattCharacteristic? = null

    // DIS characteristics
    private var disManufacturerChar: BluetoothGattCharacteristic? = null
    private var disModelChar: BluetoothGattCharacteristic? = null
    private var disSerialChar: BluetoothGattCharacteristic? = null
    private var disHardwareChar: BluetoothGattCharacteristic? = null
    private var disFirmwareChar: BluetoothGattCharacteristic? = null

    // BAS battery level characteristic
    private var batteryLevelChar: BluetoothGattCharacteristic? = null

    // ── 对外响应式状态 ────────────────────────────────────

    private val _connectionState = MutableStateFlow(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceStatus = MutableStateFlow(DeviceStatus())
    val deviceStatus: StateFlow<DeviceStatus> = _deviceStatus.asStateFlow()

    private val _targets = MutableStateFlow<List<TargetObject>>(emptyList())
    val targets: StateFlow<List<TargetObject>> = _targets.asStateFlow()

    private val _disInfo = MutableStateFlow<Map<UUID, String>>(emptyMap())
    val disInfo: StateFlow<Map<UUID, String>> = _disInfo.asStateFlow()

    /** 告警回调: (leftOrdinal, rightOrdinal) */
    var onAlertChanged: ((Int, Int) -> Unit)? = null

    // 供 UI 观察的 alert StateFlow
    private val _alertLeft = MutableStateFlow(com.motobsd.model.AlertLevel.Safe)
    val alertLeft: StateFlow<com.motobsd.model.AlertLevel> = _alertLeft.asStateFlow()
    private val _alertRight = MutableStateFlow(com.motobsd.model.AlertLevel.Safe)
    val alertRight: StateFlow<com.motobsd.model.AlertLevel> = _alertRight.asStateFlow()

    /** 上次成功连接的 MAC，用于断线重连优先直连。 */
    var lastConnectedMac: String? = null
    private var isScanning = false
    private var scanCallback: ScanCallback? = null

    // ── 公开操作 ──────────────────────────────────────────

    /** 开始扫描 MotoBSD 设备（10 秒超时）。 */
    fun startScan(adapter: BluetoothAdapter) {
        if (isScanning) return
        isScanning = true
        setConnectionState(ConnectionState.Scanning)

        val scanner = adapter.bluetoothLeScanner ?: run {
            setConnectionState(ConnectionState.Failed)
            isScanning = false
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                if (name.contains("MotoBSD", ignoreCase = true)) {
                    stopScan()
                    connectDevice(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                isScanning = false
                setConnectionState(ConnectionState.Failed)
            }
        }

        // 无 filter，扫描全部设备，靠名称 "MotoBSD" 匹配
        scanner.startScan(null, settings, scanCallback)

        // 10 秒扫描超时
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            stopScan()
        }, 10_000)
    }

    /** 停止扫描。 */
    fun stopScan() {
        isScanning = false
        scanCallback?.let {
            try { BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner?.stopScan(it) }
            catch (_: Exception) {}
            scanCallback = null
        }
        if (_connectionState.value == ConnectionState.Scanning) {
            setConnectionState(ConnectionState.Idle)
        }
    }

    private fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
        BleStateHolder.updateConnectionState(state)
    }

    fun connectDevice(device: BluetoothDevice) {
        lastConnectedMac = device.address
        setConnectionState(ConnectionState.Connecting)
        connect(device)
            .retry(3, 100)
            .useAutoConnect(false)
            .enqueue()
    }

    /** 直连已知 MAC（优先于扫描，功耗更低）。 */
    fun connectByMac(adapter: BluetoothAdapter): Boolean {
        val mac = lastConnectedMac ?: return false
        try {
            val device = adapter.getRemoteDevice(mac)
            connectDevice(device)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    fun setRadarPower(on: Boolean) {
        radarPowerChar?.let { c ->
            writeCharacteristic(c, byteArrayOf(if (on) 0x01 else 0x00)).enqueue()
        }
    }

    fun triggerDfu() {
        dfuTriggerChar?.let { c ->
            writeCharacteristic(c, byteArrayOf(0x01)).enqueue()
        }
    }

    fun systemReset() {
        systemResetChar?.let { c ->
            writeCharacteristic(c, byteArrayOf(0x01)).enqueue()
        }
    }

    // ── BleManager 必须实现的抽象方法 ─────────────────────

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

                val disService = gatt.getService(Protocol.DIS_SERVICE_UUID)
                if (disService != null) {
                    disManufacturerChar = disService.getCharacteristic(Protocol.DIS_MANUFACTURER_NAME)
                    disModelChar = disService.getCharacteristic(Protocol.DIS_MODEL_NUMBER)
                    disSerialChar = disService.getCharacteristic(Protocol.DIS_SERIAL_NUMBER)
                    disHardwareChar = disService.getCharacteristic(Protocol.DIS_HARDWARE_REVISION)
                    disFirmwareChar = disService.getCharacteristic(Protocol.DIS_FIRMWARE_REVISION)
                }

                // BAS (0x180F) battery level
                val basService = gatt.getService(Protocol.BAS_SERVICE_UUID)
                if (basService != null) {
                    batteryLevelChar = basService.getCharacteristic(Protocol.BAS_BATTERY_LEVEL)
                }
                return true
            }

            override fun onServicesInvalidated() {
                alertStatusChar = null
                targetDetailsChar = null
                deviceStatusChar = null
                radarPowerChar = null
                dfuTriggerChar = null
                systemResetChar = null
                setConnectionState(ConnectionState.Idle)
            }

            override fun initialize() {
                setConnectionState(ConnectionState.Subscribing)

                // 设置通知回调
                alertStatusChar?.let { setNotificationCallback(it).with(::onAlertStatusData) }
                targetDetailsChar?.let { setNotificationCallback(it).with(::onTargetDetailsData) }
                deviceStatusChar?.let { setNotificationCallback(it).with(::onDeviceStatusData) }

                // 启用通知
                alertStatusChar?.let { enableNotifications(it).enqueue() }
                targetDetailsChar?.let { enableNotifications(it).enqueue() }
                deviceStatusChar?.let { enableNotifications(it).enqueue() }

                // 读取 DIS
                readDisInfo()

                // 读取 BAS battery level + 订阅变化
                batteryLevelChar?.let { c ->
                    setNotificationCallback(c).with { _, data ->
                        val pct = data.value?.get(0)?.toInt()?.and(0xFF) ?: return@with
                        val current = _deviceStatus.value
                        _deviceStatus.value = current.copy(batteryPercent = pct)
                    }
                    enableNotifications(c).enqueue()
                    readCharacteristic(c).enqueue()
                }

                setConnectionState(ConnectionState.Ready)
            }
        }
    }

    // ── 通知数据处理 ──────────────────────────────────────

    private fun onAlertStatusData(device: BluetoothDevice, data: Data) {
        val (left, right) = Protocol.parseAlertStatus(data.value)
        _alertLeft.value = left
        _alertRight.value = right
        // 统一由 BleService 回调处理 swap + 分发到 BleStateHolder 和 Overlay
        onAlertChanged?.invoke(left.ordinal, right.ordinal)
    }

    private fun onTargetDetailsData(device: BluetoothDevice, data: Data) {
        val list = Protocol.parseTargetDetails(data.value)
        _targets.value = list
        BleStateHolder.updateTargets(list)
    }

    private fun onDeviceStatusData(device: BluetoothDevice, data: Data) {
        val status = Protocol.parseDeviceStatus(data.value)
        _deviceStatus.value = status
        BleStateHolder.updateDeviceStatus(status)
    }

    // ── DIS info ──────────────────────────────────────────

    private fun readDisInfo() {
        val chars = listOf(
            disManufacturerChar to Protocol.DIS_MANUFACTURER_NAME,
            disModelChar to Protocol.DIS_MODEL_NUMBER,
            disSerialChar to Protocol.DIS_SERIAL_NUMBER,
            disHardwareChar to Protocol.DIS_HARDWARE_REVISION,
            disFirmwareChar to Protocol.DIS_FIRMWARE_REVISION,
        )
        chars.forEach { pair ->
            val characteristic = pair.first ?: return@forEach
            val uuid = pair.second
            readCharacteristic(characteristic)
                .with(no.nordicsemi.android.ble.callback.DataReceivedCallback { _, data ->
                    val map = _disInfo.value.toMutableMap()
                    map[uuid] = data.getStringValue(0) ?: "--"
                    _disInfo.value = map
                    BleStateHolder.updateDisInfo(map)
                })
                .enqueue()
        }
    }
}
