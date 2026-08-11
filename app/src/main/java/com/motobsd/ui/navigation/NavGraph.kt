package com.motobsd.ui.navigation

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.motobsd.ui.dashboard.DashboardScreen
import com.motobsd.ui.device.DeviceScreen
import com.motobsd.ui.devicelist.DeviceListScreen
import com.motobsd.ui.onboarding.OnboardingScreen
import com.motobsd.ui.overlay.OverlaySettingsScreen

/**
 * 底部导航项定义。
 */
enum class BottomNav(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Dashboard("dashboard", "状态", Icons.Default.Dashboard),
    Device("device", "设备", Icons.Default.Settings),
    Overlay("overlay", "图标", Icons.Default.Layers),
}

/**
 * 路由常量。
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val DEVICE_LIST = "device_list"
}

/**
 * App 顶层导航。
 *
 * @param onboardingComplete 引导是否已完成
 * @param onHideToBackground 收起后台回调
 * @param dfuFilePickerLauncher DFU 文件选择器触发
 */
@Composable
fun AppNavGraph(
    onboardingComplete: Boolean,
    onOnboardingComplete: () -> Unit,
    onHideToBackground: () -> Unit,
    onToggleRideMode: (Boolean) -> Unit,
    onSelectFirmware: () -> Unit,
) {
    val navController = rememberNavController()
    val startDestination = if (onboardingComplete) BottomNav.Dashboard.route else Routes.ONBOARDING

    // 判断是否显示底部导航栏
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in BottomNav.entries.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    BottomNav.entries.forEach { item ->
                        val selected = navBackStackEntry?.destination?.hierarchy?.any {
                            it.route == item.route
                        } == true

                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(BottomNav.Dashboard.route) {
                DashboardScreen(
                    onNavigateToDeviceList = {
                        navController.navigate(Routes.DEVICE_LIST)
                    },
                    onHideToBackground = onHideToBackground,
                    onToggleRideMode = onToggleRideMode,
                )
            }

            composable(BottomNav.Device.route) {
                DeviceScreen(
                    onSelectFirmware = onSelectFirmware,
                )
            }

            composable(BottomNav.Overlay.route) {
                OverlaySettingsScreen()
            }

            composable(Routes.DEVICE_LIST) {
                DeviceListScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onComplete = {
                        onOnboardingComplete()
                        navController.navigate(BottomNav.Dashboard.route) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}
