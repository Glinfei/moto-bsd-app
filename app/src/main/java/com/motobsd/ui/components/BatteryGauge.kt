package com.motobsd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.motobsd.model.DeviceStatus
import com.motobsd.ui.theme.CriticalRed
import com.motobsd.ui.theme.WarningYellow

/**
 * 电量指示（骑行前必看信息，常驻 Dashboard 首屏）。
 * 温度 / USB / 雷达等次要信息由 Dashboard 的"详情"折叠区展示。
 */
@Composable
fun BatteryGauge(
    status: DeviceStatus,
    modifier: Modifier = Modifier,
) {
    val barColor = when {
        status.batteryPercent < 20 -> CriticalRed
        status.batteryPercent < 50 -> WarningYellow
        // 绿色系：与"连接中=蓝色"的语义解耦，电量不与其他状态混淆
        else -> Color(0xFF4CAF50)
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.BatteryFull,
                contentDescription = "电量",
                modifier = Modifier.size(18.dp),
                tint = barColor,
            )
            Text(
                text = "${status.batteryPercent}%",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
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
    }
}
