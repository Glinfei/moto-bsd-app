package com.motobsd.ble

import com.motobsd.model.AlertLevel
import com.motobsd.model.DeviceStatus
import com.motobsd.model.TargetObject
import java.util.UUID

/**
 * MotoBSD BLE 协议常量 + 数据解析。
 *
 * 与固件 moto-bsd/src/ble/mod.rs 完全对应。
 *
 * 自定义服务: b1d30000-9e3f-4b1e-8a3e-7f2b1c3d5e7f
 * 特性 UUID:  b1d3XXXX-9e3f-4b1e-8a3e-7f2b1c3d5e7f (XXXX = 0001~0008)
 */
object Protocol {

    // ── UUID: Base Service ────────────────────────────────
    const val BSD_SERVICE_UUID_STR = "b1d30000-9e3f-4b1e-8a3e-7f2b1c3d5e7f"
    val SERVICE_UUID: UUID = UUID.fromString(BSD_SERVICE_UUID_STR)

    // ── UUID: Custom Characteristics ──────────────────────
    private fun bsdChar(suffix: String): UUID =
        UUID.fromString("b1d3$suffix-9e3f-4b1e-8a3e-7f2b1c3d5e7f")

    val CHARACTERISTIC_ALERT_STATUS   = bsdChar("0001") // read + notify
    val CHARACTERISTIC_TARGET_DETAILS = bsdChar("0002") // notify
    val CHARACTERISTIC_DEVICE_STATUS  = bsdChar("0003") // read + notify
    val CHARACTERISTIC_RADAR_POWER    = bsdChar("0005") // read + write
    val CHARACTERISTIC_DFU_TRIGGER    = bsdChar("0007") // write
    val CHARACTERISTIC_SYSTEM_RESET   = bsdChar("0008") // write
    val CHARACTERISTIC_DEVICE_NAME   = bsdChar("0009") // read + write (UTF-8, max 20 bytes)

    // ── UUID: Standard Services ───────────────────────────
    // DIS (0x180A)
    val DIS_SERVICE_UUID             = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val DIS_MANUFACTURER_NAME        = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val DIS_MODEL_NUMBER             = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val DIS_SERIAL_NUMBER            = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
    val DIS_HARDWARE_REVISION        = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
    val DIS_FIRMWARE_REVISION        = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

    // BAS (0x180F) — Battery Service
    val BAS_SERVICE_UUID             = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BAS_BATTERY_LEVEL            = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    // CCCD descriptor (for enabling notifications)
    val CCCD_UUID                    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ── 数据解析 ──────────────────────────────────────────

    /**
     * 解析 alert_status（1 字节）。
     * hi_nibble = left alert level, lo_nibble = right alert level.
     * 0=Safe, 1=Warning, 2=Alert, 3=Critical.
     */
    fun parseAlertStatus(data: ByteArray?): Pair<AlertLevel, AlertLevel> {
        if (data == null || data.isEmpty()) return Pair(AlertLevel.Safe, AlertLevel.Safe)
        val b = data[0].toInt() and 0xFF
        val left  = AlertLevel.fromValue((b shr 4) and 0x0F)
        val right = AlertLevel.fromValue(b and 0x0F)
        return Pair(left, right)
    }

    /**
     * 解析 device_status（5 字节）:
     *   [batt_mv_lo, batt_mv_hi, temp_lo, temp_hi, flags]
     *
     * batt_mv: u16 LE, direct millivolts (VDDHDIV5, already scaled by firmware).
     * temp:    i16 LE, decidegC (e.g., 255 = 25.5°C).
     * flags:   bit4=radar powered, bit0=USB, bit1=battery not detected.
     */
    fun parseDeviceStatus(data: ByteArray?): DeviceStatus {
        if (data == null || data.size < 5) return DeviceStatus()

        val battMv = ((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF)
        val tempRaw = ((data[3].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        val tempDecideg = if (tempRaw > 32767) tempRaw - 65536 else tempRaw // i16 sign
        val flags = data[4].toInt() and 0xFF

        val tempCelsius = tempDecideg / 10f

        // Battery percentage via standard BAS 0x2A19 (set externally by BleManager).
        // Fallback: linear interpolation 3200-4200mV → 0-100%.
        val pct = if (battMv > 100) {
            ((battMv - 3200).coerceAtLeast(0) * 100 / (4200 - 3200)).coerceAtMost(100)
        } else 0

        return DeviceStatus(
            batteryVoltage = battMv,
            batteryPercent = pct,
            temperature = tempCelsius,
            usbConnected = (flags and 0x01) != 0,
            radarOnline = (flags and 0x10) != 0,
        )
    }

    /**
     * 解析 target_details 通知（≤48 字节）。
     * 帧格式: [count, (range, angle_lo, angle_hi, vel, id, level_side)*N].
     * 每目标 6 字节。
     */
    fun parseTargetDetails(data: ByteArray?): List<TargetObject> {
        if (data == null || data.size < 2) return emptyList()

        val count = data[0].toInt() and 0xFF
        if (count == 0) return emptyList()

        val targets = mutableListOf<TargetObject>()
        var offset = 1
        for (i in 0 until count) {
            if (offset + 5 >= data.size) break
            val rangeDm = data[offset].toInt() and 0xFF
            val angleLo = data[offset + 1].toInt() and 0xFF
            val angleHi = data[offset + 2].toInt() and 0xFF
            val angle = (angleLo or (angleHi shl 8)).let { if (it > 32767) it - 65536 else it } // i16
            val vel = (data[offset + 3].toInt() and 0xFF).let { if (it > 127) it - 256 else it } // i8
            val id = data[offset + 4].toInt() and 0xFF
            val ls = data[offset + 5].toInt() and 0xFF
            targets.add(TargetObject(rangeDm = rangeDm, angle = angle, velocity = vel,
                id = id, levelAndSide = ls))
            offset += 6
        }
        return targets
    }
}
