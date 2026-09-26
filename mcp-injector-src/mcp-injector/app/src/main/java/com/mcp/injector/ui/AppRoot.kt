package com.mcp.injector.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mcp.injector.ui.home.HomeScreen
import com.mcp.injector.ui.home.HomeViewModel
import com.mcp.injector.ui.manager.ManagerScreen
import com.mcp.injector.ui.manager.ManagerViewModel
import com.mcp.injector.ui.settings.AboutScreen
import com.mcp.injector.ui.settings.SettingsScreen
import com.mcp.injector.ui.settings.SettingsViewModel

/** 底部导航项（对齐反编译产物 Tab：route/label/icon）。 */
private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    Tab("home", "主页", Icons.Outlined.Home),
    Tab("settings", "设置", Icons.Outlined.Settings),
)

/**
 * 应用根（支撑文件，对齐反编译产物 AppRoot）。
 *
 * Scaffold + 底部导航 + NavHost：
 * - "home"：主页（工程列表 + 已注入应用），点击已注入应用 → "manager/{pkg}"；
 * - "settings"：设置页（顶部「关于」入口 → "about"）；
 * - "manager/{pkg}"：Manager 详情页（任务 C 新增路由，不带底部栏）；
 * - "about"：关于页（版本 / 开发者 / 群聊 / 赞助，不带底部栏）。
 */
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            // Manager 详情页与关于页隐藏底部栏（均自带返回顶栏）
            if (currentRoute != MANAGER_ROUTE && currentRoute != ABOUT_ROUTE) {
                NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            if (currentRoute != tab.route) {
                                navController.navigate(tab.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") {
                HomeScreen(
                    vm = viewModel<HomeViewModel>(),
                    onOpenManager = { pkg ->
                        navController.navigate("manager/${pkg.trim()}")
                    },
                )
            }
            composable("settings") {
                SettingsScreen(
                    vm = viewModel<SettingsViewModel>(),
                    onOpenAbout = { navController.navigate(ABOUT_ROUTE) },
                )
            }
            composable(ABOUT_ROUTE) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = MANAGER_ROUTE,
                arguments = listOf(navArgument("pkg") { type = NavType.StringType }),
            ) {
                ManagerScreen(
                    vm = viewModel<ManagerViewModel>(),
                    packageName = it.arguments?.getString("pkg") ?: "",
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

private const val MANAGER_ROUTE = "manager/{pkg}"
private const val ABOUT_ROUTE = "about"
