package com.motobsd.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.motobsd.model.OverlayConfig
import com.motobsd.model.OverlaySize
import com.motobsd.model.OverlayStyle
import com.motobsd.ui.components.StyleSelector
import com.motobsd.ui.theme.SafeGray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlaySettingsScreen(
    config: OverlayConfig,
    onConfigChange: (OverlayConfig) -> Unit,
    onResetPosition: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Style ─────────────────────────────────────────
        Text("图标样式", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider()
        StyleSelector(
            current = config.style,
            onSelect = { onConfigChange(config.copy(style = it)) },
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Size ──────────────────────────────────────────
        Text("图标大小", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OverlaySize.entries.forEach { size ->
                val selected = size == config.size
                TextButton(
                    onClick = { onConfigChange(config.copy(size = size)) },
                    colors = if (selected) ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) else ButtonDefaults.textButtonColors(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(size.dp.dp)
                            .clip(CircleShape)
                            .then(
                                if (selected) Modifier
                                else Modifier
                            ),
                    ) {
                        Text(
                            text = size.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        // ── Alpha ─────────────────────────────────────────
        Text("透明度", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider()

        Slider(
            value = config.alpha.toFloat(),
            onValueChange = { onConfigChange(config.copy(alpha = it.toInt())) },
            valueRange = 20f..100f,
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

        // ── Preview ───────────────────────────────────────
        Text("预览", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧圆点预览
                androidx.compose.foundation.Canvas(
                    modifier = Modifier.size(config.size.dp.dp)
                ) {
                    drawCircle(color = Color.Gray.copy(alpha = config.alpha / 100f), radius = size.minDimension / 2)
                }
                Text("模拟导航中...", style = MaterialTheme.typography.bodySmall, color = SafeGray)
                // 右侧圆点预览
                androidx.compose.foundation.Canvas(
                    modifier = Modifier.size(config.size.dp.dp)
                ) {
                    drawCircle(color = Color.Gray.copy(alpha = config.alpha / 100f), radius = size.minDimension / 2)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Reset ─────────────────────────────────────────
        Button(
            onClick = onResetPosition,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Text("重置为默认位置")
        }
    }
}
