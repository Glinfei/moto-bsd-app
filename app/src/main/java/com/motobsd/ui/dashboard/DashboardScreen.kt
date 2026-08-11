package com.motobsd.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motobsd.ui.components.RadarView
import com.motobsd.model.BleConnectionState
import com.motobsd.model.TargetRecord
import com.motobsd.ui.components.BatteryGauge
import com.motobsd.ui.theme.AlertOrange
import com.motobsd.ui.theme.CriticalRed
import com.motobsd.ui.theme.MotoBsdBlue
import com.motobsd.ui.theme.SafeGray
import com.motobsd.ui.theme.WarningYellow
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    onNavigateToDeviceList: () -> Unit,
    onHideToBackground: () -> Unit,
    onToggleRideMode: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lastMac by viewModel.lastMac.collectAsStateWithLifecycle()
    val lastDataAt by viewModel.lastDataAt.collectAsStateWithLifecycle()
    val rssi by viewModel.rssi.collectAsStateWithLifecycle()
    val targetRecords by viewModel.targetRecords.collectAsStateWithLifecycle()
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableStateOf(0L) }
    val context = LocalContext.current

    // 每秒刷新一次，驱动"数据新鲜度"显示
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    // 连接就绪时自动启动后台服务（通知 + 声音）
    LaunchedEffect(uiState.connectionState) {
        if (uiState.connectionState is BleConnectionState.Ready) {
            com.motobsd.service.BleService.start(context)
        }
    }

    Scaffold(
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val cs = uiState.connectionState

                    // ── 主操作按钮（全宽） ─────────────────
                    when (cs) {
                        is BleConnectionState.Disconnected,
                        is BleConnectionState.Error,
                        -> {
                            Button(
                                onClick = onNavigateToDeviceList,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MotoBsdBlue),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (cs is BleConnectionState.Error) "重试扫描" else "扫描设备")
                            }
                        }
                        is BleConnectionState.Ready -> {
                            Button(
                                onClick = { onToggleRideMode(!uiState.rideMode) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (uiState.rideMode) Color(0xFF4CAF50) else MotoBsdBlue,
                                ),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DirectionsBike,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (uiState.rideMode) "退出骑行" else "开始骑行")
                            }
                        }
                        else -> {
                            Button(
                                onClick = { viewModel.cancelConnect() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MotoBsdBlue),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text(if (cs is BleConnectionState.Scanning) "停止扫描" else "取消重连")
                            }
                        }
                    }

                    // ── 次要操作（一行小按钮） ────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            if (cs is BleConnectionState.Ready) Arrangement.SpaceEvenly
                            else Arrangement.Center,
                    ) {
                        TextButton(onClick = onHideToBackground) {
                            Icon(
                                imageVector = Icons.Default.Minimize,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("最小化")
                        }
                        if (cs is BleConnectionState.Ready) {
                            TextButton(
                                onClick = { viewModel.onDisconnect() },
                                colors = ButtonDefaults.textButtonColors(contentColor = CriticalRed),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LinkOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("断开连接")
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        // ── Header + 连接状态 ────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "MotoBSD",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            val (stateText, stateColor) = when (val cs = uiState.connectionState) {
                is BleConnectionState.Ready -> "已连接" to Color(0xFF4CAF50)
                // 忙碌态用蓝色，避免与 Warning 黄色混淆
                is BleConnectionState.Scanning -> cs.label to MotoBsdBlue
                is BleConnectionState.Connecting -> cs.label to MotoBsdBlue
                is BleConnectionState.Reconnecting -> "重连中(第${cs.attempt}次)" to MotoBsdBlue
                is BleConnectionState.Error -> "连接失败" to CriticalRed
                is BleConnectionState.Disconnected -> "未连接" to Color.Gray
            }
            ConnectionPill(
                text = stateText,
                color = stateColor,
                rssi = if (uiState.connectionState is BleConnectionState.Ready) rssi else null,
            )
        }

        // ── 断线时的一键重连入口 ─────────────────────────
        if (lastMac != null &&
            (uiState.connectionState is BleConnectionState.Disconnected ||
                uiState.connectionState is BleConnectionState.Error)
        ) {
            OutlinedButton(
                onClick = { viewModel.reconnectLast() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("重连上次设备")
            }
        }

        // ── 雷达视图（替换左右盲区卡） ────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "周围目标",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.Gray,
                    )
                    Spacer(Modifier.weight(1f))
                    val staleSecs = if (lastDataAt > 0) ((now - lastDataAt) / 1000).toInt() else -1
                    Text(
                        text = when {
                            staleSecs < 0 -> "等待数据…"
                            staleSecs <= 5 -> "实时"
                            else -> "更新于 ${staleSecs}s 前"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (staleSecs < 0 || staleSecs > 5) CriticalRed else Color.Gray,
                    )
                }
                RadarView(
                    targets = uiState.targets,
                    leftAlert = uiState.alertLeft,
                    rightAlert = uiState.alertRight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                )
                TargetRecordsLog(records = targetRecords)
            }
        }

        // ── 电量（骑行前必看，常驻） ─────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            BatteryGauge(status = uiState.deviceStatus)
        }

        // ── 设备与目标详情（次要信息，可折叠） ───────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDetails = !showDetails }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "设备与目标详情",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.Gray,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (showDetails) "收起" else "展开",
                    tint = Color.Gray,
                )
            }
            AnimatedVisibility(visible = showDetails) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (uiState.targets.isEmpty()) {
                        Text(
                            text = "暂无目标数据",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    } else {
                        Text(
                            text = "最近目标",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Gray,
                        )
                        uiState.targets.sortedBy { it.rangeM }.take(2).forEach { t ->
                            val sideLabel = when (t.side) {
                                0 -> "左"
                                1 -> "右"
                                else -> "后方"
                            }
                            val approach = when {
                                t.velocity > 0 -> "靠近"
                                t.velocity < 0 -> "远离"
                                else -> ""
                            }
                            // 无决策矩阵：颜色跟随该侧"有无目标"的当前显示状态
                            val sideAlert = when (t.side) {
                                0 -> uiState.alertLeft
                                1 -> uiState.alertRight
                                else -> com.motobsd.model.AlertLevel.Safe
                            }
                            Text(
                                text = "${sideLabel}侧 · %.1fm · %d° · ${t.velocity}m/s $approach"
                                    .format(t.rangeMeters, t.angleDeg),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (sideAlert == com.motobsd.model.AlertLevel.Safe) SafeGray else WarningYellow,
                            )
                        }
                    }

                    HorizontalDivider()

                    // 温度 / USB / 雷达（从电量卡移入详情）
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Thermostat,
                                contentDescription = "温度",
                                modifier = Modifier.size(14.dp),
                                tint = Color.Gray,
                            )
                            Text(
                                text = "${uiState.deviceStatus.temperature.toInt()}°C",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (uiState.deviceStatus.usbConnected) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Usb,
                                    contentDescription = "USB",
                                    modifier = Modifier.size(14.dp),
                                    tint = MotoBsdBlue,
                                )
                                Text(
                                    text = "USB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MotoBsdBlue,
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val radarColor =
                                if (uiState.deviceStatus.radarOnline) Color(0xFF4CAF50) else Color.Gray
                            Text(
                                text = if (uiState.deviceStatus.radarOnline) "雷达 ●" else "雷达 ○",
                                style = MaterialTheme.typography.bodySmall,
                                color = radarColor,
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ConnectionPill(text: String, color: Color, rssi: Int? = null) {
    val weak = rssi != null && rssi < WEAK_RSSI
    val effectiveColor = if (weak) AlertOrange else color
    Surface(
        shape = RoundedCornerShape(50),
        color = effectiveColor.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (rssi != null) {
                SignalBars(rssi)
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(effectiveColor),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (weak) "$text · 信号弱" else text,
                style = MaterialTheme.typography.bodyMedium,
                color = effectiveColor,
            )
        }
    }
}

/** 4 格信号条：≥-60 绿 / ≥-75 黄 / ≥-85 橙 / 其余红 */
@Composable
private fun SignalBars(rssi: Int, modifier: Modifier = Modifier) {
    val (color, level) = when {
        rssi >= -60 -> Color(0xFF4CAF50) to 4
        rssi >= -75 -> WarningYellow to 3
        rssi >= -85 -> AlertOrange to 2
        else -> CriticalRed to 1
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (i in 1..4) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((4 + i * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= level) color else Color.Gray.copy(alpha = 0.35f)),
            )
        }
    }
}

/** 目标事件记录：左/右两列，每侧最多 4 条，格式 HH:mm:ss 距离 角度 */
@Composable
private fun TargetRecordsLog(records: List<TargetRecord>, modifier: Modifier = Modifier) {
    val left = records.filter { it.angleDeg < 0 }.take(4)
    val right = records.filter { it.angleDeg > 0 }.take(4)
    if (left.isEmpty() && right.isEmpty()) return

    HorizontalDivider()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RecordColumn(title = "左", records = left, modifier = Modifier.weight(1f))
        RecordColumn(title = "右", records = right, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RecordColumn(title: String, records: List<TargetRecord>, modifier: Modifier = Modifier) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
        )
        records.forEach { rec ->
            Text(
                text = "${timeFormat.format(Date(rec.lastSeenAt))}  " +
                    "%.1fm  %d°".format(rec.rangeM.toFloat(), rec.angleDeg),
                style = MaterialTheme.typography.bodySmall,
                color = if (rec.disappeared) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/** 信号弱阈值（dBm） */
private const val WEAK_RSSI = -80
