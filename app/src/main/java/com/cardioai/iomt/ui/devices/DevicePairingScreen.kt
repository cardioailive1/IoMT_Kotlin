package com.cardioai.iomt.ui.devices

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardioai.iomt.core.AppContainer
import com.cardioai.iomt.data.ble.KnownBLEDevices
import com.cardioai.iomt.data.ble.PairingState
import kotlinx.coroutines.launch

@Composable
fun DevicePairingScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val pairingState by container.devicePairingService.pairingState.collectAsState()
    val isStreaming by container.devicePairingService.isStreaming.collectAsState()
    val framesSynced by container.devicePairingService.framesSynced.collectAsState()

    val blePermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            container.devicePairingService.startScanning()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Connect Devices", fontSize = 24.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(16.dp))

        // ── BLE ──────────────────────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bluetooth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Bluetooth Device", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))

                when (val state = pairingState) {
                    is PairingState.Idle -> {
                        Button(onClick = { permissionLauncher.launch(blePermissions) }) {
                            Text("Scan for Nearby Bluetooth Devices")
                        }
                    }
                    is PairingState.Scanning -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Looking for nearby Bluetooth devices...", fontSize = 13.sp)
                        }
                    }
                    is PairingState.Discovered -> {
                        Text("${state.devices.size} device(s) found:", fontSize = 13.sp)
                        LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                            items(state.devices) { device ->
                                val known = KnownBLEDevices.match(device.name)
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    onClick = { container.devicePairingService.connect(device.address, device.name) },
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(device.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        known?.let {
                                            Text(it.displayLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                        }
                                        Text("Signal: ${device.rssi} dBm", fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                    is PairingState.Connecting -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Connecting...", fontSize = 13.sp)
                        }
                    }
                    is PairingState.Connected -> {
                        Text(
                            if (isStreaming) "Connected — $framesSynced frames synced" else "Connected",
                            color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { container.devicePairingService.disconnect() }) {
                            Text("Disconnect")
                        }
                    }
                    is PairingState.Failed -> {
                        Text(state.reason, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { permissionLauncher.launch(blePermissions) }) {
                            Text("Try Again")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("OTHER DATA SOURCES", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        // ── Health Connect (Wear OS / Apple-Watch-equivalent) ───────────
        HealthConnectCard(container = container)

        Spacer(Modifier.height(8.dp))

        // ── Google Health (Fitbit) ──────────────────────────────────────
        GoogleHealthCard(container = container)
    }
}

@Composable
private fun HealthConnectCard(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var isConnected by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        isConnecting = false
        if (granted.containsAll(container.healthConnectService.permissionsToRequest())) {
            isConnected = true
            container.healthConnectService.startObservingHeartRate(scope) { reading ->
                scope.launch {
                    try {
                        container.apiClient.registerDevice(
                            deviceId = "health-connect-${reading.sourceApp}",
                            deviceType = "activity_tracker",
                            patientId = container.authService.currentUser?.patientId ?: "",
                            deviceName = "Health Connect (${reading.sourceApp})",
                        )
                    } catch (e: Exception) { /* best-effort */ }
                }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Watch, contentDescription = null, tint = if (isConnected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Wear OS / Health Connect", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    if (isConnected) "Connected — syncing heart rate" else "Reads heart rate from Health Connect",
                    fontSize = 11.sp,
                )
            }
            if (isConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            } else if (!isConnected) {
                TextButton(onClick = {
                    if (!container.healthConnectService.isAvailable) return@TextButton
                    isConnecting = true
                    permissionLauncher.launch(container.healthConnectService.permissionsToRequest())
                }) { Text("Connect") }
            } else {
                TextButton(onClick = {
                    container.healthConnectService.stopObserving()
                    isConnected = false
                }) { Text("Disconnect") }
            }
        }
    }
}

@Composable
private fun GoogleHealthCard(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var isConnected by remember { mutableStateOf(container.googleHealthService.isConnected) }
    var isConnecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Fitbit (via Google Health)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        if (isConnected) "Connected — syncing heart rate" else "Sign in with your Google Account (Fitbit)",
                        fontSize = 11.sp,
                    )
                    error?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
                }
                if (isConnecting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else if (!isConnected) {
                    TextButton(onClick = {
                        isConnecting = true
                        scope.launch {
                            val success = container.googleHealthService.connect()
                            isConnecting = false
                            isConnected = success
                            error = container.googleHealthService.lastError
                            if (success) {
                                container.googleHealthService.startPolling(scope) { reading ->
                                    scope.launch {
                                        try {
                                            container.apiClient.registerDevice(
                                                deviceId = "google-health-fitbit",
                                                deviceType = "activity_tracker",
                                                patientId = container.authService.currentUser?.patientId ?: "",
                                                deviceName = "Fitbit",
                                            )
                                        } catch (e: Exception) { /* best-effort */ }
                                    }
                                }
                            }
                        }
                    }) { Text("Connect") }
                } else {
                    TextButton(onClick = {
                        container.googleHealthService.disconnect()
                        isConnected = false
                    }) { Text("Disconnect") }
                }
            }
        }
    }
}
