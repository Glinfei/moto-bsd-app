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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.motobsd.ui.theme.CriticalRed
import com.motobsd.ui.theme.MotoBsdBlue

data class DisInfo(
    val manufacturer: String = "--",
    val model: String = "--",
    val serial: String = "--",
    val hardwareRev: String = "--",
    val firmwareRev: String = "--",
)

@Composable
fun DeviceScreen(
    disInfo: DisInfo,
    radarOn: Boolean,
    onRadarToggle: (Boolean) -> Unit,
    onSystemReset: () -> Unit,
    onSelectFirmware: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showResetDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Device Info ───────────────────────────────────
        Text("设备信息", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider()

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("产品型号", disInfo.model)
                InfoRow("序列号", disInfo.serial)
                InfoRow("硬件版本", disInfo.hardwareRev)
                InfoRow("固件版本", disInfo.firmwareRev)
                InfoRow("制造商", disInfo.manufacturer)
            }
        }

        // ── DFU ───────────────────────────────────────────
        Text("固件升级", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider()

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前版本: ${disInfo.firmwareRev}", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onSelectFirmware) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Text("  选择升级包")
                }
            }
        }

        // ── Radar Settings ────────────────────────────────
        Text("雷达设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider()

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("雷达电源", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = radarOn, onCheckedChange = onRadarToggle)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── System ────────────────────────────────────────
        Text("系统", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider()

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MotoBsdBlue),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("  重启设备")
                }
                Button(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CriticalRed.copy(alpha = 0.8f)),
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null)
                    Text("  恢复出厂设置")
                }
            }
        }
    }

    // ── Reset Confirmation Dialog ─────────────────────────
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("确认操作") },
            text = { Text("确定要重启设备吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    onSystemReset()
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
