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
     * hi_nibble = 模块左侧有无目标, lo_nibble = 模块右侧有无目标 (0/1)。
     * 固件只标记"有目标"且不感知安装方向；App 按 [toRiderAngle] 转换后使用。
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

        // device_status 帧不含百分比：这里算出的只是"设备无 BAS 时"的线性兜底。
        // 一旦本连接收到 BAS 2A19，百分比由 BAS 独占 —— 见 [mergeBatteryPercent]。
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
     * 合并 device_status 与 BAS 2A19 的电量百分比。
     *
     * device_status 帧本身不含百分比，[parseDeviceStatus] 的线性换算只是"设备无 BAS 时"
     * 的兜底。只要本连接已收到 BAS 值，百分比就由 BAS 独占（DESIGN §5.2），device_status
     * 仅更新电压/温度/flags —— 否则固件每 5s 一次的 device_status 推送会用线性值覆盖
     * BAS，且在 LiPo 平台区（3.5~3.8V）把电量高估数倍。
     *
     * @param status 解析出的 device_status
     * @param basPercent 本连接已收到的 BAS 值；null = 尚无 BAS，保留线性兜底
     */
    fun mergeBatteryPercent(status: DeviceStatus, basPercent: Int?): DeviceStatus =
        if (basPercent == null) status else status.copy(batteryPercent = basPercent)

    /** 连续两次电量采样相差超过此值（百分点）即视为读数不稳定 */
    const val BATTERY_PERCENT_STABLE_TOLERANCE = 3

    /**
     * 电量读数是否稳定：与前一次采样相差不超过 [BATTERY_PERCENT_STABLE_TOLERANCE] 个百分点。
     *
     * 固件在连接建立时可能先返回缓存值（开机 / 上次连接写入，连接后 5s 才刷新），
     * 只有连续两次相近的读数才认为可信，避免把缓存值当实时电量显示。
     */
    fun isBatteryPercentStable(previous: Int?, current: Int): Boolean =
        previous != null && kotlin.math.abs(current - previous) <= BATTERY_PERCENT_STABLE_TOLERANCE

    /**
     * 把模块原始角度转换为骑手视角角度。
     *
     * 模块朝后安装时（默认）模块右手方向 = 骑手左手方向，因此左右反转。
     * 所有告警/威胁/雷达图应使用转换后的角度；仅模块原始视角展示才用 raw。
     */
    fun toRiderAngle(rawAngleDeg: Int, radarFacesRear: Boolean): Int =
        if (radarFacesRear) -rawAngleDeg else rawAngleDeg

    /**
     * 解析 target_details 通知（≤48 字节）。
     * 帧格式: [count: u8, (range_m: i8, angle_deg: i8, velocity_ms: i8, obj_id: u8) × N].
     * 每目标 4 字节，最多 8 个目标（固件零裁剪透传，任何角度/距离/速度都上报）。
     * 角度为模块原始坐标：负=模块左侧、正=模块右侧、0=正后方；
     * 速度正=靠近、负=远离。骑手视角转换用 [toRiderAngle]。
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
