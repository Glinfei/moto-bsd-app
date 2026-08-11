package com.motobsd.ui.devicelist

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motobsd.model.BleConnectionState
import com.motobsd.ui.theme.MotoBsdBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    onNavigateBack: () -> Unit,
    viewModel: DeviceListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showManualDialog by remember { mutableStateOf(false) }
    var manualMac by remember { mutableStateOf("") }
    val context = LocalContext.current
    val bluetoothAdapter =
        (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // 连接成功后自动返回
    LaunchedEffect(uiState.connectionState) {
        if (uiState.connectionState is BleConnectionState.Ready && uiState.connectingMac == null) {
            onNavigateBack()
        }
    }

    // 错误提示
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onClearMessage()
        }
    }

    // 自动开始扫描
    LaunchedEffect(Unit) {
        if (bluetoothAdapter?.isEnabled == true && !uiState.scanning) {
            viewModel.startScan()
        }
    }

    val filteredDevices = remember(uiState.devices, uiState.nameFilter) {
        viewModel.getFilteredDevices()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("选择设备") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stopScan()
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showManualDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "手动输入MAC")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            // ── 扫描控制栏 ────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (uiState.scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MotoBsdBlue,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "发现 ${uiState.devices.size} 台",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        "发现 ${uiState.devices.size} 台设备",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                    )
                }
                Spacer(Modifier.weight(1f))

                // 停止/扫描按钮
                OutlinedButton(
                    onClick = { viewModel.toggleScan() },
                ) {
                    if (uiState.scanning) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFFF44336),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("停止")
                    } else {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("扫描")
                    }
                }
            }

            // ── 名称过滤 ──────────────────────────────────
            OutlinedTextField(
                value = uiState.nameFilter,
                onValueChange = { viewModel.setFilter(it) },
                placeholder = { Text("按名称过滤...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                },
                trailingIcon = {
                    if (uiState.nameFilter.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setFilter("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "清除", modifier = Modifier.size(20.dp))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            // ── 设备列表 ──────────────────────────────────
            if (!viewModel.isBluetoothEnabled(bluetoothAdapter)) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color.Gray,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("请先打开手机蓝牙", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                    }
                }
            } else if (filteredDevices.isEmpty() && !uiState.scanning) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.BluetoothSearching,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color.Gray,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("未发现设备", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (uiState.nameFilter.isNotEmpty()) "没有名称匹配「${uiState.nameFilter}」的设备"
                            else "点击「扫描」按钮开始搜索",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    }
                }
            } else if (filteredDevices.isEmpty() && uiState.scanning) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), color = MotoBsdBlue)
                        Spacer(Modifier.height(16.dp))
                        Text("扫描中...", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                        if (uiState.nameFilter.isNotEmpty()) {
                            Text(
                                "正在寻找「${uiState.nameFilter}」",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredDevices, key = { it.device.address }) { result ->
                        DeviceItem(
                            result = result,
                            isConnecting = uiState.connectingMac == result.device.address,
                            isConnected = uiState.connectionState is BleConnectionState.Ready &&
                                uiState.connectedMac == result.device.address,
                            onClick = {
                                if (uiState.connectingMac == null) {
                                    viewModel.selectDevice(result.device.address)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // 手动输入 MAC 对话框
    if (showManualDialog) {
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            title = { Text("手动输入 MAC 地址") },
            text = {
                OutlinedTextField(
                    value = manualMac,
                    onValueChange = { manualMac = it },
                    label = { Text("MAC 地址 (如 AB:CD:EF:01:02:03)") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showManualDialog = false
                    if (manualMac.isNotBlank()) {
                        viewModel.selectDevice(manualMac.trim())
                    }
                }) { Text("连接") }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DeviceItem(
    result: ScanResult,
    isConnecting: Boolean,
    isConnected: Boolean,
    onClick: () -> Unit,
) {
    val name = result.scanRecord?.deviceName ?: result.device.name ?: "未知设备"
    val mac = result.device.address ?: "??:??:??:??:??:??"
    val rssi = result.rssi
    val isMotoBsd = name.contains("MotoBSD", ignoreCase = true)

    val rssiColor = when {
        rssi > -60 -> Color(0xFF4CAF50)
        rssi > -80 -> Color(0xFFFFC107)
        else -> Color(0xFFF44336)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isConnecting && !isConnected) { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isMotoBsd) MotoBsdBlue.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isMotoBsd) FontWeight.Bold else FontWeight.Normal,
                    )
                    if (isMotoBsd) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "MotoBSD",
                            style = MaterialTheme.typography.labelSmall,
                            color = MotoBsdBlue,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = mac,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MotoBsdBlue,
                    )
                } else if (isConnected) {
                    Text(
                        text = "已连接",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4CAF50),
                    )
                } else {
                    Text(
                        text = "${rssi}dBm",
                        style = MaterialTheme.typography.bodyMedium,
                        color = rssiColor,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
