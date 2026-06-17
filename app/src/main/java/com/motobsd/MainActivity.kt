package com.motobsd

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.motobsd.ble.MotoBsdBleManager
import com.motobsd.model.BleStateHolder
import com.motobsd.model.ConnectionState
import com.motobsd.model.OverlayConfig
import com.motobsd.model.OverlaySize
import com.motobsd.model.OverlayStyle
import com.motobsd.service.BleService
import com.motobsd.service.OverlayService
import com.motobsd.ui.screens.DashboardScreen
import com.motobsd.ui.screens.DeviceScreen
import com.motobsd.ui.screens.DisInfo
import com.motobsd.ui.screens.OnboardingScreen
import com.motobsd.ui.screens.OverlaySettingsScreen
import com.motobsd.ui.theme.MotoBSDTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.motobsd.service.DfuService
import no.nordicsemi.android.dfu.DfuServiceInitiator
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    // Overlay config
    private val _overlayConfig = MutableStateFlow(OverlayConfig())

    // DFU file picker
    private val dfuFilePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { startDfu(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("motobsd", MODE_PRIVATE)

        // Check permissions before any BLE work
        requestPermissions()

        val onboardingComplete = prefs.getBoolean("onboarding_complete", false)

        setContent {
            MotoBSDTheme {
                if (!onboardingComplete) {
                    OnboardingScreen(
                        onComplete = {
                            prefs.edit().putBoolean("onboarding_complete", true).apply()
                            // reload
                            recreate()
                        }
                    )
                } else {
                    MainApp()
                }
            }
        }
    }

    @Composable
    private fun MainApp() {
        val connectionState by BleStateHolder.connectionState.collectAsState()
        val alertLeft by BleStateHolder.alertLeft.collectAsState()
        val alertRight by BleStateHolder.alertRight.collectAsState()
        val deviceStatus by BleStateHolder.deviceStatus.collectAsState()
        val disInfoMap by BleStateHolder.disInfo.collectAsState()
        val targets by BleStateHolder.targets.collectAsState()
        val overlayConfig by _overlayConfig.collectAsState()

        val disInfo = DisInfo(
            manufacturer = disInfoMap[UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")] ?: "--",
            model = disInfoMap[UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")] ?: "--",
            serial = disInfoMap[UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")] ?: "--",
            hardwareRev = disInfoMap[UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")] ?: "--",
            firmwareRev = disInfoMap[UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")] ?: "--",
        )

        var selectedTab by rememberSaveable { mutableIntStateOf(0) }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                        label = { Text("状态") },
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("设备") },
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Layers, contentDescription = null) },
                        label = { Text("图标") },
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                    )
                }
            }
        ) { padding ->
            when (selectedTab) {
                0 -> DashboardScreen(
                    connectionState = connectionState,
                    alertLeft = alertLeft,
                    alertRight = alertRight,
                    deviceStatus = deviceStatus,
                    targets = targets,
                    onScan = {
                        BleService.scan(this@MainActivity)
                    },
                    onDisconnect = {
                        BleService.disconnect(this@MainActivity)
                    },
                    onHideToBackground = {
                        OverlayService.start(this@MainActivity)
                        moveTaskToBack(true)
                    },
                    modifier = Modifier.padding(padding),
                )
                1 -> DeviceScreen(
                    disInfo = disInfo,
                    radarOn = deviceStatus.radarOnline,
                    onRadarToggle = { on ->
                        BleService.bleManager?.setRadarPower(on)
                    },
                    onSystemReset = { BleService.bleManager?.systemReset() },
                    onSelectFirmware = { dfuFilePicker.launch("application/zip") },
                    modifier = Modifier.padding(padding),
                )
                2 -> OverlaySettingsScreen(
                    config = overlayConfig,
                    onConfigChange = { cfg ->
                        _overlayConfig.value = cfg
                        saveConfig(cfg)
                        OverlayService.updateConfig(this@MainActivity)
                    },
                    onResetPosition = {
                        prefs.edit().apply {
                            remove("left_x"); remove("left_y")
                            remove("right_x"); remove("right_y")
                        }.apply()
                        OverlayService.start(this@MainActivity)
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

    // ── DFU ───────────────────────────────────────────────

    private fun startDfu(zipUri: Uri) {
        val address = prefs.getString("last_mac", null)
        if (address == null) {
            Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            return
        }
        // 低速高可靠性 DFU：小 MTU + 每包确认，防止 Flash 写失败
        val starter = DfuServiceInitiator(address)
            .setZip(zipUri)
            .setUnsafeExperimentalButtonlessServiceInSecureDfuEnabled(true)
            .setPacketsReceiptNotificationsEnabled(true)
            .setPacketsReceiptNotificationsValue(1)   // 每包都确认，最可靠
            .setMtu(23)                                // 最小 MTU，减少丢包
            .setNumberOfRetries(5)                     // 失败重试 5 次
        starter.start(this, DfuService::class.java)
    }

    // ── permissions ───────────────────────────────────────

    private fun requestPermissions() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }

        // Check overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
        }
    }

    private fun saveConfig(cfg: OverlayConfig) {
        prefs.edit().apply {
            putInt("overlay_style", cfg.style.ordinal)
            putInt("overlay_size", cfg.size.ordinal)
            putInt("overlay_alpha", cfg.alpha)
            putBoolean("overlay_swap", cfg.swapLeftRight)
        }.apply()
        // 同步到全局
        BleService.swapLeftRight = cfg.swapLeftRight
        BleStateHolder.swapLeftRight = cfg.swapLeftRight
    }

    override fun onResume() {
        super.onResume()
        val swap = prefs.getBoolean("overlay_swap", false)
        // 同步 swap 标志到两个消费端
        BleService.swapLeftRight = swap
        BleStateHolder.swapLeftRight = swap
        _overlayConfig.value = OverlayConfig(
            style = OverlayStyle.entries.getOrElse(
                prefs.getInt("overlay_style", OverlayStyle.Dot.ordinal)
            ) { OverlayStyle.Dot },
            size = OverlaySize.entries.getOrElse(
                prefs.getInt("overlay_size", OverlaySize.Large.ordinal)
            ) { OverlaySize.Large },
            alpha = prefs.getInt("overlay_alpha", 60),
            swapLeftRight = swap,
        )
    }
}
