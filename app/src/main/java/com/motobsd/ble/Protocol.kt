package com.motobsd.ble

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
     * hi_nibble = left 有无目标, lo_nibble = right 有无目标 (0/1)。
     * 固件只标记"有目标"，不再给出告警等级；App 按有无直接驱动告警显示。
     */
    fun parseAlertStatus(data: ByteArray?): Pair<Boolean, Boolean> {
        if (data == null || data.isEmpty()) return Pair(false, false)
        val b = data[0].toInt() and 0xFF
        val left  = ((b shr 4) and 0x0F) != 0
        val right = (b and 0x0F) != 0
        return Pair(left, right)
    }

    /**
     * 解析 device_status（5 字节）:
     *   [batt_mv_lo, batt_mv_hi, temp_lo, temp_hi, flags]
     *
     * batt_mv: u16 LE, direct millivolts (VDDHDIV5, already scaled by firmware).
     * temp:    i16 LE, decidegC (e.g., 255 = 25.5°C).
     * flags:   bit0=USB connected, bit4=radar powered/online.
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
     * 帧格式: [count: u8, (range_m: i8, angle_deg: i8, velocity_ms: i8, obj_id: u8) × N].
     * 每目标 4 字节，最多 8 个目标（固件零裁剪透传，任何角度/距离/速度都上报）。
     * 角度负=左、正=右、0=正后方；速度正=靠近、负=远离。
     */
    fun parseTargetDetails(data: ByteArray?): List<TargetObject> {
        if (data == null || data.size < 1) return emptyList()

        val count = data[0].toInt() and 0xFF

        val targets = mutableListOf<TargetObject>()
        var offset = 1
        for (i in 0 until count) {
            if (offset + 4 > data.size) break
            val range = data[offset].toInt() and 0xFF
            val angle = data[offset + 1].toInt() and 0xFF
            val vel   = data[offset + 2].toInt() and 0xFF
            val id    = data[offset + 3].toInt() and 0xFF
            targets.add(
                TargetObject(
                    rangeM = if (range > 127) range - 256 else range,   // i8
                    angleDeg = if (angle > 127) angle - 256 else angle,  // i8
                    velocity = if (vel > 127) vel - 256 else vel,        // i8
                    id = id,
                )
            )
            offset += 4
        }
        return targets
    }
}
