package com.motobsd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.motobsd.model.OverlayStyle
import com.motobsd.ui.theme.MotoBsdBlue

/**
 * 图标样式选择器（圆点/竖条/箭头）。
 */
@Composable
fun StyleSelector(
    current: OverlayStyle,
    onSelect: (OverlayStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OverlayStyle.entries.forEach { style ->
            val selected = style == current
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (selected) Modifier.border(2.dp, MotoBsdBlue, RoundedCornerShape(8.dp))
                        else Modifier.border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    )
                    .clickable { onSelect(style) }
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 预览
                when (style) {
                    OverlayStyle.LightBar -> Box(
                        modifier = Modifier
                            .size(width = 16.dp, height = 24.dp)
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        (if (selected) MotoBsdBlue else Color.Gray),
                                        Color.Transparent,
                                    )
                                )
                            )
                    )
                    OverlayStyle.Dot -> Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(if (selected) MotoBsdBlue else Color.Gray)
                    )
                    OverlayStyle.Bar -> Box(
                        modifier = Modifier
                            .size(width = 6.dp, height = 24.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (selected) MotoBsdBlue else Color.Gray)
                    )
                    OverlayStyle.Arrow -> Text(
                        text = "←→",
                        color = if (selected) MotoBsdBlue else Color.Gray,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = style.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MotoBsdBlue else Color.Gray,
                )
            }
        }
    }
}
