package com.motobsd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.motobsd.model.AlertLevel
import com.motobsd.model.ConnectionState
import com.motobsd.model.DeviceStatus
import com.motobsd.model.TargetObject
import com.motobsd.ui.theme.CriticalRed
import com.motobsd.ui.theme.SafeGray
import com.motobsd.ui.theme.WarningYellow
import com.motobsd.ui.components.BatteryGauge
import com.motobsd.ui.components.BlindSpotCard
import com.motobsd.ui.theme.MotoBsdBlue

@Composable
fun DashboardScreen(
    connectionState: ConnectionState,
    alertLeft: AlertLevel,
    alertRight: AlertLevel,
    deviceStatus: DeviceStatus,
    targets: List<TargetObject>,
    onHideToBackground: () -> Unit,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Header ────────────────────────────────────────
        Text(
            text = "MotoBSD",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        // Connection badge
        val (stateText, stateColor) = when (connectionState) {
            ConnectionState.Ready -> "⚡ 已连接" to androidx.compose.ui.graphics.Color(0xFF4CAF50)
            ConnectionState.Scanning, ConnectionState.Connecting, ConnectionState.Subscribing, ConnectionState.Reconnecting ->
                "⟳ ${connectionState.label}" to androidx.compose.ui.graphics.Color(0xFFFFC107)
            ConnectionState.Failed -> "✗ ${connectionState.label}" to androidx.compose.ui.graphics.Color(0xFFF44336)
            ConnectionState.Idle -> "○ 未连接" to androidx.compose.ui.graphics.Color.Gray
        }
        Text(text = stateText, color = stateColor, style = MaterialTheme.typography.bodyMedium)

        // ── Blind Spot Cards ──────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BlindSpotCard("左", alertLeft, modifier = Modifier.weight(1f))
            BlindSpotCard("右", alertRight, modifier = Modifier.weight(1f))
        }

        // ── Nearest Target ────────────────────────────────
        if (targets.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("最近目标", style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color.Gray)
                    targets.sortedBy { it.rangeDm }.take(2).forEach { t ->
                        val sideLabel = if (t.side == 0) "左" else "右"
                        val approach = if (t.velocity > 0) "靠近" else if (t.velocity < 0) "远离" else ""
                        Text(
                            text = "${sideLabel}侧 · ${t.rangeMeters}m · ${t.angle}° · ${t.velocity}m/s $approach",
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (t.threatLevel) {
                                2 -> CriticalRed; 1 -> WarningYellow; else -> SafeGray
                            },
                        )
                    }
                }
            }
        }

        // ── Device Status ─────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            BatteryGauge(status = deviceStatus)
        }

        Spacer(Modifier.height(8.dp))

        // ── Scan / Disconnect ─────────────────────────────
        when (connectionState) {
            ConnectionState.Idle, ConnectionState.Failed -> {
                Button(
                    onClick = onScan,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MotoBsdBlue),
                ) {
                    Text(if (connectionState == ConnectionState.Failed) "重试扫描" else "扫描设备")
                }
            }
            ConnectionState.Ready -> {
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFFF44336)
                    ),
                ) {
                    Text("断开连接")
                }
            }
            else -> {
                // Scanning / Connecting / Subscribing / Reconnecting
                Button(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    enabled = false,
                    colors = ButtonDefaults.buttonColors(containerColor = MotoBsdBlue),
                ) {
                    Text(connectionState.label)
                }
            }
        }

        // ── Hide to Background ────────────────────────────
        Button(
            onClick = onHideToBackground,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MotoBsdBlue),
        ) {
            Text("收起后台")
        }
    }
}
