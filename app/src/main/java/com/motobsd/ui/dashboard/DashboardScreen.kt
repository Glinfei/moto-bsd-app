package com.motobsd.ui.dashboard

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motobsd.model.BleConnectionState
import com.motobsd.ui.components.BatteryGauge
import com.motobsd.ui.components.BlindSpotCard
import com.motobsd.ui.theme.CriticalRed
import com.motobsd.ui.theme.MotoBsdBlue
import com.motobsd.ui.theme.SafeGray
import com.motobsd.ui.theme.WarningYellow

@Composable
fun DashboardScreen(
    onNavigateToDeviceList: () -> Unit,
    onHideToBackground: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 连接就绪时自动启动后台服务（通知 + 声音）
    LaunchedEffect(uiState.connectionState) {
        if (uiState.connectionState is BleConnectionState.Ready) {
            com.motobsd.service.BleService.start(context)
        }
    }

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
        val (stateText, stateColor) = when (val cs = uiState.connectionState) {
            is BleConnectionState.Ready -> "⚡ 已连接" to Color(0xFF4CAF50)
            is BleConnectionState.Scanning -> "⟳ ${cs.label}" to Color(0xFFFFC107)
            is BleConnectionState.Connecting -> "⟳ ${cs.label}" to Color(0xFFFFC107)
            is BleConnectionState.Reconnecting -> "⟳ ${cs.label} (第${cs.attempt}次)" to Color(0xFFFFC107)
            is BleConnectionState.Error -> "✗ ${cs.message}" to Color(0xFFF44336)
            is BleConnectionState.Disconnected -> "○ 未连接" to Color.Gray
        }
        Text(text = stateText, color = stateColor, style = MaterialTheme.typography.bodyMedium)

        // ── Blind Spot Cards ──────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val leftTarget = uiState.targets.filter { it.side == 0 }.minByOrNull { it.rangeDm }
            val rightTarget = uiState.targets.filter { it.side == 1 }.minByOrNull { it.rangeDm }
            BlindSpotCard("左", uiState.alertLeft, Modifier.weight(1f),
                nearestDistMeters = leftTarget?.rangeMeters, nearestVel = leftTarget?.velocity)
            BlindSpotCard("右", uiState.alertRight, Modifier.weight(1f),
                nearestDistMeters = rightTarget?.rangeMeters, nearestVel = rightTarget?.velocity)
        }

        // ── Nearest Target ────────────────────────────────
        if (uiState.targets.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "最近目标",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray,
                    )
                    uiState.targets.sortedBy { it.rangeDm }.take(2).forEach { t ->
                        val sideLabel = if (t.side == 0) "左" else "右"
                        val approach = when {
                            t.velocity > 0 -> "靠近"
                            t.velocity < 0 -> "远离"
                            else -> ""
                        }
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
            BatteryGauge(status = uiState.deviceStatus)
        }

        Spacer(Modifier.height(8.dp))

        // ── Scan / Disconnect ─────────────────────────────
        when (uiState.connectionState) {
            is BleConnectionState.Disconnected,
            is BleConnectionState.Error,
                -> {
                Button(
                    onClick = onNavigateToDeviceList,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MotoBsdBlue),
                ) {
                    Text(
                        if (uiState.connectionState is BleConnectionState.Error) "重试扫描"
                        else "扫描设备"
                    )
                }
            }
            is BleConnectionState.Ready -> {
                Button(
                    onClick = { viewModel.onDisconnect() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                ) {
                    Text("断开连接")
                }
            }
            else -> {
                Button(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    enabled = false,
                    colors = ButtonDefaults.buttonColors(containerColor = MotoBsdBlue),
                ) {
                    Text(uiState.connectionState.label)
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
