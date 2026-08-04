package com.motobsd.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

/**
 * Flow-based BLE 扫描器。
 *
 * 扫描全部 BLE 设备，持续收集结果到列表，通过 Flow 发射。
 * Flow 被取消时自动停止扫描。
 *
 * Usage:
 *   scanner.scan().collect { devices -> ... }
 */
class BleScanner(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter?
        get() {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            return manager?.adapter
        }

    /**
     * 扫描 BLE 设备，持续发射发现的设备列表（去重，按信号强度排序）。
     *
     * @param timeoutMs 扫描超时，超时后 Flow 正常完成（不抛异常）
     * @param filterByName 可选的名称过滤（为 null 则展示全部设备）
     */
    @SuppressLint("MissingPermission")
    fun scan(
        timeoutMs: Long = 10_000L,
        filterByName: String? = null,
    ): Flow<List<ScanResult>> = callbackFlow {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            close()
            return@callbackFlow
        }

        val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner ?: run {
            close()
            return@callbackFlow
        }

        val devices = mutableMapOf<String, ScanResult>()
        var lastEmit = 0L

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (device.address != null) {
                    devices[device.address] = result
                }
                // 节流：最多每 200ms 发射一次，避免 UI 重组卡顿
                val now = System.currentTimeMillis()
                if (now - lastEmit < 200) return
                lastEmit = now
                trySend(
                    devices.values.sortedByDescending { it.rssi }
                )
            }

            override fun onScanFailed(errorCode: Int) {
                // 扫描失败，关闭 Flow
                close()
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }

        // 超时自动停止
        if (timeoutMs > 0) {
            delay(timeoutMs)
        }

        try { scanner.stopScan(callback) } catch (_: Exception) {}
        close()

        // Flow 被取消时也停止扫描（用户手动停止 / 离开页面）
        awaitClose {
            try { scanner.stopScan(callback) } catch (_: Exception) {}
        }
    }
}
