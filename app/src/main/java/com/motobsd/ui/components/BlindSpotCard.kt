package com.motobsd.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.motobsd.model.AlertLevel
import com.motobsd.ui.theme.CriticalBg
import com.motobsd.ui.theme.CriticalRed
import com.motobsd.ui.theme.SafeBg
import com.motobsd.ui.theme.SafeGray
import com.motobsd.ui.theme.WarningBg
import com.motobsd.ui.theme.WarningYellow

/**
 * 单侧盲区状态卡片（左右各一）。
 */
@Composable
fun BlindSpotCard(
    sideLabel: String,
    level: AlertLevel,
    modifier: Modifier = Modifier,
    nearestDistMeters: Float? = null,
    nearestVel: Int? = null,
) {
    val bgColor by animateColorAsState(
        when (level) {
            AlertLevel.Safe -> SafeBg.copy(alpha = 0.15f)
            AlertLevel.Warning -> WarningBg.copy(alpha = 0.25f)
            AlertLevel.Alert -> WarningBg.copy(alpha = 0.4f)
            AlertLevel.Critical -> CriticalBg.copy(alpha = 0.25f)
        },
        label = "cardBg",
    )
    val dotColor by animateColorAsState(
        when (level) {
            AlertLevel.Safe -> SafeGray
            AlertLevel.Warning -> WarningYellow
            AlertLevel.Alert -> WarningYellow
            AlertLevel.Critical -> CriticalRed
        },
        label = "dotColor",
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = sideLabel,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(dotColor),
                contentAlignment = Alignment.Center,
            ) {
                if (level == AlertLevel.Alert || level == AlertLevel.Critical) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = "告警",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Text(
                text = level.label,
                style = MaterialTheme.typography.titleMedium,
                color = dotColor,
                textAlign = TextAlign.Center,
            )

            if (nearestDistMeters != null && level != AlertLevel.Safe) {
                val distText = "%.1fm".format(nearestDistMeters)
                val velText = nearestVel?.let { v ->
                    when { v > 0 -> "  →${v}m/s"; v < 0 -> "  ←${-v}m/s"; else -> "" }
                } ?: ""
                Text(
                    text = "$distText$velText",
                    style = MaterialTheme.typography.bodySmall,
                    color = dotColor.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
