package com.motobsd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.motobsd.model.DeviceStatus
import com.motobsd.ui.theme.CriticalRed
import com.motobsd.ui.theme.MotoBsdBlue
import com.motobsd.ui.theme.WarningYellow

/**
 * 电量 + 温度 + USB 状态指示。
 */
@Composable
fun BatteryGauge(
    status: DeviceStatus,
    modifier: Modifier = Modifier,
) {
    val barColor = when {
        status.batteryPercent < 20 -> CriticalRed
        status.batteryPercent < 50 -> WarningYellow
        else -> MotoBsdBlue
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.BatteryFull,
                contentDescription = "电量",
                modifier = Modifier.size(20.dp),
                tint = barColor,
            )
            Text(
                text = "${status.batteryPercent}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = String.format("%.1fV", status.batteryVoltage / 1000f),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
        LinearProgressIndicator(
            progress = { status.batteryPercent / 100f },
            modifier = Modifier.fillMaxWidth(),
            color = barColor,
            trackColor = barColor.copy(alpha = 0.2f),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Thermostat,
                    contentDescription = "温度",
                    modifier = Modifier.size(16.dp),
                    tint = Color.Gray,
                )
                Text(
                    text = "${status.temperature.toInt()}°C",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (status.usbConnected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Usb,
                        contentDescription = "USB",
                        modifier = Modifier.size(16.dp),
                        tint = MotoBsdBlue,
                    )
                    Text(
                        text = "USB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MotoBsdBlue,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val radarColor = if (status.radarOnline) Color(0xFF4CAF50) else Color.Gray
                Text(
                    text = if (status.radarOnline) "雷达 ●" else "雷达 ○",
                    style = MaterialTheme.typography.bodySmall,
                    color = radarColor,
                )
            }
        }
    }
}
