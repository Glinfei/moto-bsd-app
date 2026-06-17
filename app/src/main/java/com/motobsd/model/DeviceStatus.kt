package com.motobsd.model

/**
 * 设备状态（battery, temperature, system flags）。
 * 对应 device_status 特征值 5 字节: [batt_lo, batt_hi, temp_lo, temp_hi, flags].
 */
data class DeviceStatus(
    /** mV */
    val batteryVoltage: Int = 0,
    /** raw ADC value for battery level */
    val batteryRaw: Int = 0,
    /** percentage (0-100), 由 BleManager 根据电压曲线计算 */
    val batteryPercent: Int = 0,
    /** 摄氏度 (raw: centi-degC) */
    val temperature: Float = 0f,
    /** bit[0]=USB connected */
    val usbConnected: Boolean = false,
    /** bit[1]=radar online */
    val radarOnline: Boolean = false,
)
