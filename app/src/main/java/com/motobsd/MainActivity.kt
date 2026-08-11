package com.motobsd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.motobsd.data.ble.BleRepository
import com.motobsd.data.settings.SettingsRepository
import com.motobsd.model.BleConnectionState
import com.motobsd.service.BleService
import com.motobsd.service.DfuService
import com.motobsd.service.OverlayService
import com.motobsd.ui.navigation.AppNavGraph
import com.motobsd.ui.theme.MotoBSDTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import no.nordicsemi.android.dfu.DfuServiceInitiator
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var bleRepository: BleRepository

    private var onboardingComplete = false
    /** 本次进程内是否已引导过悬浮窗权限，避免每次 onResume 都强制跳转系统设置页 */
    private var overlayPermissionPrompted = false

    // DFU file picker
    private val dfuFilePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { startDfu(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load onboarding state
        lifecycleScope.launch {
            onboardingComplete = settingsRepository.onboardingComplete.first()
            setupUI()
        }
    }

    private fun setupUI() {
        setContent {
            MotoBSDTheme {
                AppNavGraph(
                    onboardingComplete = onboardingComplete,
                    onOnboardingComplete = {
                        lifecycleScope.launch {
                            settingsRepository.setOnboardingComplete()
                            onboardingComplete = true
                            setupUI()
                        }
                    },
                    onHideToBackground = {
                        BleService.start(this)
                        OverlayService.start(this)
                        moveTaskToBack(true)
                    },
                    onToggleRideMode = { enabled -> toggleRideMode(enabled) },
                    onSelectFirmware = {
                        dfuFilePicker.launch("application/zip")
                    },
                )
            }
        }
    }

    // ── Permissions ───────────────────────────────────────

    private fun requestPermissions() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) missing.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }

        // Check overlay permission（仅提示一次，拒绝后不再反复打断）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this) && !overlayPermissionPrompted
        ) {
            overlayPermissionPrompted = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
        }
    }

    // ── DFU ───────────────────────────────────────────────

    private fun startDfu(zipUri: Uri) {
        lifecycleScope.launch {
            val address = settingsRepository.lastMac.first()
            if (address == null) {
                Toast.makeText(this@MainActivity, "请先连接设备", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 先写 dfu_trigger(0x01) 让固件复位进 bootloader，再交给 Nordic DFU 库传输
            val triggered = bleRepository.enterDfuMode()
            if (!triggered) {
                Toast.makeText(this@MainActivity, "无法触发 DFU：设备未连接", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val starter = DfuServiceInitiator(address)
                .setZip(zipUri)
                .setUnsafeExperimentalButtonlessServiceInSecureDfuEnabled(true)
                .setPacketsReceiptNotificationsEnabled(true)
                .setPacketsReceiptNotificationsValue(8)
                .setNumberOfRetries(5)
            starter.start(this@MainActivity, DfuService::class.java)
        }
    }

    // ── Ride mode ─────────────────────────────────────────

    /**
     * 骑行模式：一键进入骑行状态。
     * 进入：要求 BLE 已就绪 → 启动 BLE/悬浮窗服务 + 悬浮窗保持屏幕常亮 + 切到后台；
     * 退出：关闭屏幕常亮（服务继续运行，由用户自行决定是否断开）。
     */
    private fun toggleRideMode(enabled: Boolean) {
        if (enabled) {
            if (bleRepository.connectionState.value !is BleConnectionState.Ready) {
                Toast.makeText(this, "请先连接 MotoBSD 设备", Toast.LENGTH_SHORT).show()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(this)
            ) {
                Toast.makeText(
                    this,
                    "未开启悬浮窗权限，骑行模式将无法保持屏幕常亮",
                    Toast.LENGTH_LONG,
                ).show()
            }
            BleService.start(this)
            OverlayService.setRideMode(this, true)
            lifecycleScope.launch { settingsRepository.setRideModeEnabled(true) }
            moveTaskToBack(true)
        } else {
            OverlayService.setRideMode(this, false)
            lifecycleScope.launch { settingsRepository.setRideModeEnabled(false) }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        requestPermissions()
    }
}
