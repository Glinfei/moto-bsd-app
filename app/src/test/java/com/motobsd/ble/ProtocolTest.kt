package com.motobsd.ble

import com.motobsd.model.TargetObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    // ── alert_status ─────────────────────────────────────

    @Test
    fun `alert status null or empty means safe on both sides`() {
        val fromNull = Protocol.parseAlertStatus(null)
        assertFalse(fromNull.first)
        assertFalse(fromNull.second)

        val fromEmpty = Protocol.parseAlertStatus(byteArrayOf())
        assertFalse(fromEmpty.first)
        assertFalse(fromEmpty.second)
    }

    @Test
    fun `alert status nibbles map to left and right`() {
        assertEquals(true to false, Protocol.parseAlertStatus(byteArrayOf(0x10)))
        assertEquals(false to true, Protocol.parseAlertStatus(byteArrayOf(0x01)))
        assertEquals(true to true, Protocol.parseAlertStatus(byteArrayOf(0x11)))
        assertEquals(true to true, Protocol.parseAlertStatus(byteArrayOf(0x33)))
        assertEquals(false to false, Protocol.parseAlertStatus(byteArrayOf(0x00)))
    }

    // ── device_status ────────────────────────────────────

    @Test
    fun `device status null or too short returns defaults`() {
        val fromNull = Protocol.parseDeviceStatus(null)
        assertEquals(0, fromNull.batteryVoltage)
        assertEquals(0, fromNull.batteryPercent)
        assertEquals(0f, fromNull.temperature, 0f)
        assertFalse(fromNull.usbConnected)
        assertFalse(fromNull.radarOnline)

        val fromShort = Protocol.parseDeviceStatus(byteArrayOf(1, 2, 3, 4))
        assertEquals(0, fromShort.batteryVoltage)
    }

    @Test
    fun `device status parses little endian battery temperature and flags`() {
        // batt = 0x0F88 = 3976mV → 77%；temp = 0x00FF = 255 decidegC = 25.5°C；flags = USB + radar
        val status = Protocol.parseDeviceStatus(
            byteArrayOf(0x88.toByte(), 0x0F, 0xFF.toByte(), 0x00, 0x11)
        )
        assertEquals(3976, status.batteryVoltage)
        assertEquals(77, status.batteryPercent)
        assertEquals(25.5f, status.temperature, 0.001f)
        assertTrue(status.usbConnected)
        assertTrue(status.radarOnline)
    }

    @Test
    fun `device status parses negative temperature`() {
        // temp = 0xFF9C = -100 decidegC = -10.0°C；batt = 0x0C80 = 3200mV → 0%
        val status = Protocol.parseDeviceStatus(
            byteArrayOf(0x80.toByte(), 0x0C, 0x9C.toByte(), 0xFF.toByte(), 0x00)
        )
        assertEquals(-10.0f, status.temperature, 0.001f)
        assertEquals(0, status.batteryPercent)
    }

    // ── target_details ───────────────────────────────────

    @Test
    fun `target details empty when null or zero count`() {
        assertTrue(Protocol.parseTargetDetails(null).isEmpty())
        assertTrue(Protocol.parseTargetDetails(byteArrayOf(0)).isEmpty())
    }

    @Test
    fun `target details parse one signed target`() {
        val list = Protocol.parseTargetDetails(
            byteArrayOf(1, 0xFD.toByte(), 0x14, 0x06, 0x07)
        )
        assertEquals(1, list.size)
        val t = list[0]
        assertEquals(-3, t.rangeM)
        assertEquals(20, t.angleDeg)
        assertEquals(6, t.velocity)
        assertEquals(7, t.id)
    }

    @Test
    fun `target details parse multiple targets`() {
        val count = 8
        val bytes = mutableListOf<Byte>(count.toByte())
        for (i in 0 until count) {
            bytes.add((-i).toByte())      // range（带符号）
            bytes.add((i * 10).toByte())  // angle
            bytes.add((i - 4).toByte())   // velocity
            bytes.add((i + 1).toByte())   // id
        }
        val list = Protocol.parseTargetDetails(bytes.toByteArray())
        assertEquals(count, list.size)

        assertEquals(0, list[0].rangeM)
        assertEquals(0, list[0].angleDeg)
        assertEquals(-4, list[0].velocity)
        assertEquals(1, list[0].id)

        assertEquals(-7, list[7].rangeM)
        assertEquals(70, list[7].angleDeg)
        assertEquals(8, list[7].id)
    }

    @Test
    fun `target details truncates malformed frames`() {
        // count 声称 3，但只有 1 个目标的字节，应解析出 1 个而不是崩溃
        val list = Protocol.parseTargetDetails(byteArrayOf(3, 1, 2, 3, 4))
        assertEquals(1, list.size)
        assertEquals(1, list[0].rangeM)
    }

    // ── TargetObject.side ────────────────────────────────

    @Test
    fun `target object side mapping`() {
        assertEquals(0, TargetObject(angleDeg = -10).side)  // 左
        assertEquals(1, TargetObject(angleDeg = 10).side)   // 右
        assertEquals(-1, TargetObject(angleDeg = 0).side)   // 正后方
    }
}
