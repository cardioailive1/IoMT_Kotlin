package com.cardioai.iomt.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardioai.iomt.core.AppContainer
import com.cardioai.iomt.data.models.BridgeStatus
import com.cardioai.iomt.data.network.ApiError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<BridgeStatus?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    val bridgeState by container.bridgeClient.connectionState.collectAsState()
    val isProvisioned = container.bridgeClient.isProvisioned

    suspend fun refresh() {
        isRefreshing = true
        try {
            status = container.apiClient.fetchStatus()
            errorMessage = null
        } catch (e: ApiError) {
            errorMessage = e.message
        } catch (e: Exception) {
            errorMessage = e.message
        }
        isRefreshing = false
    }

    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            delay(5000)
            refresh()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Dashboard", fontSize = 24.sp, fontWeight = FontWeight.Black)
            IconButton(onClick = { scope.launch { refresh() } }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }

        Spacer(Modifier.height(8.dp))

        val user = container.authService.currentUser
        Text("Hello, ${user?.name ?: user?.email ?: "there"}", fontSize = 16.sp)

        Spacer(Modifier.height(12.dp))

        Row {
            // Bridge status chip — only shown if this device has actually
            // been provisioned for hardware-bridge streaming. Mirrors the
            // same fix applied to DashboardView.swift: showing "not
            // connected" for a feature a typical patient never touches is
            // just noise, not useful information.
            if (isProvisioned) {
                AssistChip(
                    onClick = {},
                    label = { Text(bridgeState.patientFacingLabel) },
                )
                Spacer(Modifier.width(8.dp))
            }
        }

        errorMessage?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("Could not refresh: $msg", fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        status?.let { s ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Devices: ${s.devices.total} (${s.devices.active} active)", fontSize = 14.sp)
                    Text("Agents running: ${s.agentCount}", fontSize = 14.sp)
                    Text("Messages processed: ${s.messageBusTotal}", fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(16.dp))

            Text("Devices", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(s.devices.devices) { device ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(device.deviceType ?: "Unknown device", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("Frames: ${device.dataCount}", fontSize = 11.sp)
                            }
                            Text(
                                if (device.isActive) "Active" else "Inactive",
                                fontSize = 12.sp,
                                color = if (device.isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        } ?: run {
            if (!isRefreshing) {
                Text("No data yet.", fontSize = 13.sp)
            }
        }
    }
}
