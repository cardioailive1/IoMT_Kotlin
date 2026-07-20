package com.cardioai.iomt.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardioai.iomt.BuildConfig
import com.cardioai.iomt.core.AppContainer
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onOpenSubscriptionSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val user = container.authService.currentUser
    val isPatient = (user?.role?.lowercase() ?: "") == "patient"
    val bridgeState by container.bridgeClient.connectionState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(16.dp))

        SectionCard("Account") {
            LabeledRow("Name", user?.name ?: "—")
            LabeledRow("Email", user?.email ?: "—")
            // Role and Patient ID are internal bookkeeping, not meaningful
            // to a patient looking at their own settings — shown only for
            // clinical staff. Mirrors the exact fix applied to
            // SettingsView.swift on iOS.
            if (!isPatient) {
                LabeledRow("Role", user?.role?.replaceFirstChar { it.uppercase() } ?: "—")
                user?.patientId?.let { LabeledRow("Patient ID", it) }
            }
            LabeledRow("Signed in with", if (isPatient) "Google" else "Email")

            // Subscription management and account deletion — visible to
            // everyone, patients included (unlike Backend Connection/
            // Security below, which are clinical-staff only).
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenSubscriptionSettings) {
                Text("Subscription & Account")
            }
        }

        Spacer(Modifier.height(12.dp))

        // Backend Connection + Security sections: same gating as iOS —
        // this entire category describes the real-time hardware bridge,
        // which ordinary patients never touch. Clinical/admin users may
        // still need it for support purposes.
        if (!isPatient) {
            SectionCard("Backend Connection") {
                LabeledRow("Status", bridgeState.description)
                LabeledRow("Backend", container.apiClient.baseUrl.ifBlank { "—" })
            }
            Spacer(Modifier.height(12.dp))
            SectionCard("Security") {
                LabeledRow("Auth method", "Email/Password or Google")
                LabeledRow("WS auth", "HMAC-SHA256 + JWT")
            }
            Spacer(Modifier.height(12.dp))
        }

        SectionCard("About") {
            LabeledRow("Version", BuildConfig.VERSION_NAME)
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = { scope.launch { container.authService.signOut() } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign Out", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        Text(value, fontSize = 12.sp)
    }
}
