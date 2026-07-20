package com.cardioai.iomt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cardioai.iomt.core.AppContainer
import com.cardioai.iomt.data.auth.AuthState
import com.cardioai.iomt.ui.alerts.AlertsScreen
import com.cardioai.iomt.ui.auth.LoginScreen
import com.cardioai.iomt.ui.dashboard.DashboardScreen
import com.cardioai.iomt.ui.devices.DevicePairingScreen
import com.cardioai.iomt.ui.paywall.PaywallScreen
import com.cardioai.iomt.ui.settings.SettingsScreen
import com.cardioai.iomt.ui.settings.SubscriptionSettingsScreen

private sealed class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Dashboard : Tab("dashboard", "Dashboard", Icons.Filled.Favorite)
    object Connect : Tab("connect", "Connect", Icons.Filled.Bluetooth)
    object Alerts : Tab("alerts", "Alerts", Icons.Filled.Notifications)
    object Settings : Tab("settings", "Settings", Icons.Filled.Settings)
}

private val bottomTabs = listOf(Tab.Dashboard, Tab.Connect, Tab.Alerts, Tab.Settings)

@Composable
fun CardioAINavHost(container: AppContainer) {
    val authState by container.authService.authState.collectAsState()

    when (authState) {
        is AuthState.SignedOut, is AuthState.Loading -> {
            LoginScreen(container = container)
        }
        is AuthState.SignedIn -> {
            // NOTE: unlike an earlier iOS bug this session fixed, signing
            // in NEVER gates behind HMAC/hardware-bridge provisioning here
            // either — that's an optional feature for hospital-owned
            // devices only, configured later in Settings if needed at all.
            //
            // Subscription gate: added on top of the above, NOT a
            // reversion of it — this checks Play Billing subscription
            // entitlement, a completely different concern from
            // hardware-bridge provisioning. See BillingManager.kt for the
            // caveat that this only gates the app UI, not the backend API.
            val isSubscribed by container.billingManager.isSubscribed.collectAsState()
            val isBillingLoading by container.billingManager.isLoading.collectAsState()

            when {
                isBillingLoading -> LoadingScreen("Checking subscription...")
                !isSubscribed -> PaywallScreen(container = container)
                else -> MainAppScaffold(container = container)
            }
        }
    }
}

@Composable
private fun LoadingScreen(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(message)
        }
    }
}

@Composable
private fun MainAppScaffold(container: AppContainer) {
    val navController: NavHostController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bottomTabs.forEach { tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Dashboard.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Tab.Dashboard.route) { DashboardScreen(container = container) }
            composable(Tab.Connect.route) { DevicePairingScreen(container = container) }
            composable(Tab.Alerts.route) { AlertsScreen(container = container) }
            composable(Tab.Settings.route) {
                SettingsScreen(
                    container = container,
                    onOpenSubscriptionSettings = { navController.navigate("subscription_account") },
                )
            }
            composable("subscription_account") {
                SubscriptionSettingsScreen(
                    container = container,
                    onAccountDeleted = {
                        // AuthService already flipped to SignedOut — the
                        // top-level CardioAINavHost `when` block picks
                        // that up automatically and shows LoginScreen.
                        // No explicit navigation call needed here.
                    },
                )
            }
        }
    }
}
