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
import com.motobsd.data.settings.SettingsRepository
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

    private var onboardingComplete = false

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

        // Check overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
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
            val starter = DfuServiceInitiator(address)
                .setZip(zipUri)
                .setUnsafeExperimentalButtonlessServiceInSecureDfuEnabled(true)
                .setPacketsReceiptNotificationsEnabled(true)
                .setPacketsReceiptNotificationsValue(8)
                .setNumberOfRetries(5)
            starter.start(this@MainActivity, DfuService::class.java)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        requestPermissions()
    }
}
