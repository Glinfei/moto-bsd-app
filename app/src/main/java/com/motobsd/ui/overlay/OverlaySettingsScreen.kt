package com.motobsd.ui.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motobsd.model.LightBarOrientation
import com.motobsd.model.OverlaySize
import com.motobsd.ui.theme.AlertOrange
import com.motobsd.ui.theme.CriticalRed
import com.motobsd.ui.theme.MotoBsdBlue
import com.motobsd.ui.theme.SafeGray
import com.motobsd.ui.theme.WarningYellow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlaySettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: OverlayViewModel = hiltViewModel(),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val overlayRunning by viewModel.overlayRunning.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 悬浮窗默认开启：开关为开时自动启动（偏好持久化，关掉后不再自动弹）
    LaunchedEffect(overlayRunning) {
        if (overlayRunning) {
            com.motobsd.service.OverlayService.start(context)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Size ──────────────────────────────────────────
        Text("灯带粗细", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OverlaySize.entries.forEach { size ->
                val selected = size == config.size
                TextButton(
                    onClick = { viewModel.updateConfig(config.copy(size = size)) },
                    colors = if (selected) ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) else ButtonDefaults.textButtonColors(),
                ) {
                    Text(
                        text = size.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        // ── Alpha ─────────────────────────────────────────
        Text("透明度", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider()

        Slider(
            value = config.alpha.toFloat(),
            onValueChange = { viewModel.updateConfig(config.copy(alpha = it.toInt())) },
            valueRange = 35f..100f,
            steps = 0,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Text(
            text = "${config.alpha}%",
            style = MaterialTheme.typography.bodyMedium,
            color = SafeGray,
        )

        Spacer(Modifier.height(8.dp))

        // ── Test Alert ────────────────────────────────────
        val testLeft by viewModel.testLeft.collectAsStateWithLifecycle()
        val testRight by viewModel.testRight.collectAsStateWithLifecycle()

        Text("测试告警", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("左: ${testLeft.label}", style = MaterialTheme.typography.bodyMedium)
                val leftColor = when (testLeft) {
                    com.motobsd.model.AlertLevel.Safe -> SafeGray
                    com.motobsd.model.AlertLevel.Warning -> WarningYellow
                    com.motobsd.model.AlertLevel.Alert -> AlertOrange
                    com.motobsd.model.AlertLevel.Critical -> CriticalRed
                }
                Button(
                    onClick = { viewModel.toggleTestLeft() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = leftColor),
                ) { Text("切换") }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("右: ${testRight.label}", style = MaterialTheme.typography.bodyMedium)
                val rightColor = when (testRight) {
                    com.motobsd.model.AlertLevel.Safe -> SafeGray
                    com.motobsd.model.AlertLevel.Warning -> WarningYellow
                    com.motobsd.model.AlertLevel.Alert -> AlertOrange
                    com.motobsd.model.AlertLevel.Critical -> CriticalRed
                }
                Button(
                    onClick = { viewModel.toggleTestRight() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = rightColor),
                ) { Text("切换") }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { viewModel.resetTest() }) {
            Text("重置为安全")
        }

        Spacer(Modifier.height(8.dp))

        // ── Sound ────────────────────────────────────────
        val soundVolume by viewModel.soundVolume.collectAsStateWithLifecycle()
        val leftFreq by viewModel.leftFreq.collectAsStateWithLifecycle()
        val rightFreq by viewModel.rightFreq.collectAsStateWithLifecycle()
        val mediaStream by viewModel.mediaStream.collectAsStateWithLifecycle()

        Text("声音设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider()

        // 音量
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("音量", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = soundVolume.toFloat(),
                onValueChange = { viewModel.updateSoundVolume(it.toInt()) },
                valueRange = 0f..100f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Text("${soundVolume}%", style = MaterialTheme.typography.bodyMedium)
        }

        // 音量跟随的音频流
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("告警音量跟随", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (mediaStream) "媒体音量（推荐，支持蓝牙耳机）"
                    else "闹钟音量（可穿透其他声音）",
                    style = MaterialTheme.typography.bodySmall,
                    color = SafeGray,
                )
            }
            Switch(
                checked = mediaStream,
                onCheckedChange = { viewModel.updateStreamMode(it) },
            )
        }

        // 左频率
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("左频", style = MaterialTheme.typography.bodyMedium, color = SafeGray)
            Slider(
                value = leftFreq.toFloat(),
                onValueChange = { viewModel.updateLeftFreq(it.toInt()) },
                valueRange = 100f..2000f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = MotoBsdBlue,
                    activeTrackColor = MotoBsdBlue,
                ),
            )
            Text("${leftFreq}Hz", style = MaterialTheme.typography.bodyMedium)
        }

        // 右频率
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("右频", style = MaterialTheme.typography.bodyMedium, color = SafeGray)
            Slider(
                value = rightFreq.toFloat(),
                onValueChange = { viewModel.updateRightFreq(it.toInt()) },
                valueRange = 100f..2000f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = MotoBsdBlue,
                    activeTrackColor = MotoBsdBlue,
                ),
            )
            Text("${rightFreq}Hz", style = MaterialTheme.typography.bodyMedium)
        }

        // 左右独立试听按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { viewModel.previewLeft() },
                modifier = Modifier.weight(1f),
            ) {
                Text("🔊 试听左声")
            }
            OutlinedButton(
                onClick = { viewModel.previewRight() },
                modifier = Modifier.weight(1f),
            ) {
                Text("🔊 试听右声")
            }
        }
        // Critical 试听
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { viewModel.previewCriticalLeft() },
                modifier = Modifier.weight(1f),
            ) { Text("⚠ 试听左急") }
            OutlinedButton(
                onClick = { viewModel.previewCriticalRight() },
                modifier = Modifier.weight(1f),
            ) { Text("⚠ 试听右急") }
        }

        Spacer(Modifier.height(8.dp))

        // ── Swap Left/Right ───────────────────────────────
        Text("高级", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("左右反转", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (config.swapLeftRight) "已反转" else "正常",
                style = MaterialTheme.typography.bodySmall,
                color = SafeGray,
            )
            Switch(
                checked = config.swapLeftRight,
                onCheckedChange = { viewModel.updateConfig(config.copy(swapLeftRight = it)) },
            )
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { viewModel.toggleLightBarOrientation() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (config.lightBarOrientation == LightBarOrientation.Vertical)
                    "光带位置：左右边缘（点击切上下边缘）"
                else
                    "光带位置：上下边缘（点击切左右边缘）"
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── 悬浮窗开关 ────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("悬浮窗指示", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (overlayRunning) "当前显示中" else "当前已关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = SafeGray,
                )
            }
            Switch(
                checked = overlayRunning,
                onCheckedChange = { on ->
                    if (on) {
                        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                            Settings.canDrawOverlays(context)
                        if (!hasPermission) {
                            Toast.makeText(context, "请先在系统设置中开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                        } else {
                            com.motobsd.service.OverlayService.start(context)
                            viewModel.setOverlayRunning(true)
                        }
                    } else {
                        com.motobsd.service.OverlayService.stop(context)
                        viewModel.setOverlayRunning(false)
                    }
                },
            )
        }
    }
}
