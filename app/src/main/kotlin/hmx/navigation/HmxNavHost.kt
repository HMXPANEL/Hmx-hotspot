package hmx.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import hmx.ui.screens.activity.ActivityScreen
import hmx.ui.screens.devices.DevicesScreen
import hmx.ui.screens.error.ErrorRouteScreen
import hmx.ui.screens.home.HomeScreen
import hmx.ui.screens.onboarding.RoleSelectScreen
import hmx.ui.screens.onboarding.SplashScreen
import hmx.ui.screens.onboarding.WelcomeScreen
import hmx.ui.screens.provider.DeviceDetailsScreen
import hmx.ui.screens.provider.PairingScreen
import hmx.ui.screens.provider.ProviderDashboardScreen
import hmx.ui.screens.provider.SharingActiveScreen
import hmx.ui.screens.settings.AboutScreen
import hmx.ui.screens.settings.DataLimitsScreen
import hmx.ui.screens.settings.DiagnosticsScreen
import hmx.ui.screens.settings.NotificationsScreen
import hmx.ui.screens.settings.SecurityScreen
import hmx.ui.screens.settings.SettingsScreen
import hmx.ui.screens.user.ConnectedScreen
import hmx.ui.screens.user.ConnectingScreen
import hmx.ui.screens.user.DeviceFoundScreen
import hmx.ui.screens.user.EnterCodeScreen
import hmx.ui.screens.user.ScannerScreen
import hmx.ui.screens.user.UseDashboardScreen
import hmx.ui.screens.user.VpnPermissionScreen

object Routes {
    const val SPLASH = "splash"
    const val WELCOME = "welcome"
    const val ROLE = "role"
    const val HOME = "home"
    const val PROVIDER_DASHBOARD = "provider/dashboard"
    const val PAIRING = "provider/pairing"
    const val SHARING = "provider/sharing"
    const val DEVICE_DETAILS = "provider/device/{id}"
    const val USE_DASHBOARD = "user/dashboard"
    const val ENTER_CODE = "user/code"
    const val SCANNER = "user/scanner"
    const val FOUND = "user/found"
    const val VPN_PERM = "user/vpnperm"
    const val CONNECTING = "user/connecting"
    const val CONNECTED = "user/connected"
    const val DEVICES = "devices"
    const val ACTIVITY = "activity"
    const val SETTINGS = "settings"
    const val SETTINGS_SECURITY = "settings/security"
    const val SETTINGS_DATA = "settings/data"
    const val SETTINGS_NOTIFICATIONS = "settings/notifications"
    const val DIAGNOSTICS = "diagnostics"
    const val ABOUT = "about"
    const val ERROR = "error/{key}"

    fun deviceDetails(id: String) = "provider/device/$id"
    fun error(key: String) = "error/$key"

    val bottomBarRoutes = setOf(HOME, DEVICES, ACTIVITY, SETTINGS)
}

private data class BottomItem(val route: String, val label: String, val glyph: String)

@Composable
private fun HmxBottomBar(current: String, nav: NavHostController) {
    val items = listOf(
        BottomItem(Routes.HOME, "Home", "⌂"),
        BottomItem(Routes.DEVICES, "Devices", "▦"),
        BottomItem(Routes.ACTIVITY, "Activity", "≡"),
        BottomItem(Routes.SETTINGS, "Settings", "⚙"),
    )
    NavigationBar {
        items.forEach { item ->
            NavigationBarItem(
                selected = current == item.route,
                onClick = { navTo(nav, item.route) },
                icon = {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        androidx.compose.material3.Text(item.glyph)
                    }
                },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
fun HmxApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Routes.SPLASH

    Scaffold(
        bottomBar = {
            if (currentRoute in Routes.bottomBarRoutes) {
                HmxBottomBar(currentRoute, navController)
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SPLASH,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.SPLASH) { SplashScreen { navTo(navController, Routes.WELCOME) } }
            composable(Routes.WELCOME) { WelcomeScreen(onGetStarted = { navTo(navController, Routes.ROLE) }) }
            composable(Routes.ROLE) {
                RoleSelectScreen(
                    onProvider = { navTo(navController, Routes.PROVIDER_DASHBOARD) },
                    onUser = { navTo(navController, Routes.USE_DASHBOARD) },
                )
            }
            composable(Routes.HOME) {
                HomeScreen(
                    openProvider = { navTo(navController, Routes.PROVIDER_DASHBOARD) },
                    openUser = { navTo(navController, Routes.USE_DASHBOARD) },
                    onError = { key -> navTo(navController, Routes.error(key)) },
                )
            }

            composable(Routes.PROVIDER_DASHBOARD) {
                ProviderDashboardScreen(
                    onStartSharing = { navTo(navController, Routes.PAIRING) },
                    onOpenSharing = { navTo(navController, Routes.SHARING) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.PAIRING) {
                PairingScreen(
                    onApproved = {
                        navTo(navController, Routes.SHARING) {
                            popUpTo(Routes.PROVIDER_DASHBOARD) { inclusive = true }
                        }
                    },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable(Routes.SHARING) {
                SharingActiveScreen(onStopped = { backHome(navController) }, onDeviceClick = { id -> navTo(navController, Routes.deviceDetails(id)) })
            }
            composable(Routes.DEVICE_DETAILS) { entry ->
                DeviceDetailsScreen(deviceId = entry.arguments?.getString("id") ?: "", onBack = { navController.popBackStack() })
            }

            composable(Routes.USE_DASHBOARD) {
                UseDashboardScreen(
                    onEnterCode = { navTo(navController, Routes.ENTER_CODE) },
                    onScanQr = { navTo(navController, Routes.SCANNER) },
                )
            }
            composable(Routes.ENTER_CODE) {
                EnterCodeScreen(
                    onConnectedFlow = { navTo(navController, Routes.FOUND) },
                    onError = { key -> navTo(navController, Routes.error(key)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SCANNER) {
                ScannerScreen(
                    onCodeScanned = { code -> navTo(navController, Routes.ENTER_CODE) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.FOUND) {
                DeviceFoundScreen(onConfirmed = { navTo(navController, Routes.VPN_PERM) }, onCancel = { backHome(navController) })
            }
            composable(Routes.VPN_PERM) {
                VpnPermissionScreen(
                    onProceed = { navTo(navController, Routes.CONNECTING) },
                    onDenied = { navTo(navController, Routes.error("VPN_PERMISSION_DENIED")) },
                )
            }
            composable(Routes.CONNECTING) {
                ConnectingScreen(
                    onConnected = { navTo(navController, Routes.CONNECTED) { popUpTo(Routes.HOME) { inclusive = false } } },
                    onError = { key -> navTo(navController, Routes.error(key)) },
                )
            }
            composable(Routes.CONNECTED) {
                ConnectedScreen(onDisconnected = { backHome(navController) })
            }

            composable(Routes.DEVICES) { DevicesScreen(openDetails = { id -> navTo(navController, Routes.deviceDetails(id)) }) }
            composable(Routes.ACTIVITY) { ActivityScreen() }
            composable(Routes.SETTINGS) { SettingsScreen(
                onSecurity = { navTo(navController, Routes.SETTINGS_SECURITY) },
                onData = { navTo(navController, Routes.SETTINGS_DATA) },
                onNotifications = { navTo(navController, Routes.SETTINGS_NOTIFICATIONS) },
                onDiagnostics = { navTo(navController, Routes.DIAGNOSTICS) },
                onAbout = { navTo(navController, Routes.ABOUT) },
            ) }
            composable(Routes.SETTINGS_SECURITY) { SecurityScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.SETTINGS_DATA) { DataLimitsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.SETTINGS_NOTIFICATIONS) { NotificationsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.ABOUT) { AboutScreen(onBack = { navController.popBackStack() }) }

            composable(Routes.ERROR) { entry ->
                ErrorRouteScreen(
                    errorKey = entry.arguments?.getString("key") ?: "UNKNOWN",
                    onAction = { backHome(navController) },
                    onDismiss = { backHome(navController) },
                )
            }
        }
    }
}

private fun navTo(nav: NavHostController, route: String, builder: androidx.navigation.NavOptionsBuilder.() -> Unit = {}) {
    nav.navigate(route) {
        launchSingleTop = true
        builder()
    }
}

private fun backHome(nav: NavHostController) {
    nav.navigate(Routes.HOME) {
        popUpTo(nav.graph.findStartDestination().id)
        launchSingleTop = true
    }
}
