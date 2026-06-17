package com.motobsd.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Motorcycle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.motobsd.ui.theme.MotoBsdBlue

/**
 * 首次引导流程（4 屏）。
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AnimatedContent(targetState = step, label = "onboarding") { currentStep ->
            when (currentStep) {
                0 -> OnboardingPage(
                    icon = { Icon(Icons.Default.Motorcycle, contentDescription = null, modifier = Modifier.size(80.dp), tint = MotoBsdBlue) },
                    title = "MotoBSD",
                    subtitle = "摩托车盲区检测助手\n\n骑行中屏幕显示盲区指示\n连接 MotoBSD 设备开始",
                )
                1 -> OnboardingPage(
                    icon = { Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(80.dp), tint = MotoBsdBlue) },
                    title = "蓝牙连接",
                    subtitle = "MotoBSD 通过蓝牙与设备通信\n\n请授予蓝牙和定位权限\n打开手机蓝牙",
                )
                2 -> OnboardingPage(
                    icon = { Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(80.dp), tint = MotoBsdBlue) },
                    title = "悬浮指示",
                    subtitle = "骑行时在屏幕边缘显示盲区指示\n\n请授予悬浮窗权限\n示范拖拽操作",
                )
                3 -> OnboardingPage(
                    icon = { Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(80.dp), tint = MotoBsdBlue) },
                    title = "告警通知",
                    subtitle = "告警时发送通知\n绕过勿扰模式\n\n搜索附近的 MotoBSD 设备",
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = {
                if (step < 3) step++
                else onComplete()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MotoBsdBlue),
        ) {
            Text(if (step < 3) "下一步" else "开始使用")
        }
    }
}

@Composable
private fun OnboardingPage(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
    }
}
