package com.cardioai.iomt.ui.alerts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardioai.iomt.core.AppContainer
import com.cardioai.iomt.data.models.AlertLevel
import com.cardioai.iomt.data.models.RPMAlert
import com.cardioai.iomt.data.network.ApiError
import kotlinx.coroutines.delay

@Composable
fun AlertsScreen(container: AppContainer) {
    var alerts by remember { mutableStateOf<List<RPMAlert>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                alerts = container.apiClient.fetchAlerts()
                errorMessage = null
            } catch (e: ApiError) {
                errorMessage = e.message
            } catch (e: Exception) {
                errorMessage = e.message
            }
            delay(5000)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Alerts", fontSize = 24.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(12.dp))

        errorMessage?.let {
            Text("Could not refresh: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }

        if (alerts.isEmpty()) {
            Text("No active alerts.", fontSize = 13.sp)
        } else {
            LazyColumn {
                items(alerts) { alert ->
                    AlertCard(alert)
                }
            }
        }
    }
}

@Composable
private fun AlertCard(alert: RPMAlert) {
    val color = when (alert.alertLevel) {
        AlertLevel.critical -> MaterialTheme.colorScheme.error
        AlertLevel.high -> MaterialTheme.colorScheme.error
        AlertLevel.medium -> MaterialTheme.colorScheme.tertiary
        AlertLevel.low -> MaterialTheme.colorScheme.outline
    }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                Text(
                    alert.alertLevel.name.uppercase(),
                    color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(alert.description, fontSize = 14.sp)
            if (alert.requiredActions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                alert.requiredActions.forEach { action ->
                    Text("• $action", fontSize = 11.sp)
                }
            }
        }
    }
}
