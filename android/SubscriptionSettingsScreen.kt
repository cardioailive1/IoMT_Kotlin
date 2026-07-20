package com.cardioai.iomt.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardioai.iomt.core.AppContainer
import kotlinx.coroutines.launch

@Composable
fun SubscriptionSettingsScreen(container: AppContainer, onAccountDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isSubscribed by container.billingManager.isSubscribed.collectAsState()

    var showDeleteConfirm1 by remember { mutableStateOf(false) }
    var showDeleteConfirm2 by remember { mutableStateOf(false) }
    var isDeletingAccount by remember { mutableStateOf(false) }
    var deletionError by remember { mutableStateOf<String?>(null) }
    var deletionSuccessMessage by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Subscription & Account", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Subscription Status", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                LabeledRow("Plan", "CardioAI Live Premium")
                LabeledRow("Status", if (isSubscribed) "Active" else "Inactive")
                Text(
                    "Renewal date is shown on Google Play's subscription page below — " +
                        "it isn't available directly in the app on Android.",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = {
                        (context as? android.app.Activity)?.let {
                            container.billingManager.openManageSubscriptions(it)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Renew, Cancel, or Change Plan")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Opens Google Play's subscription management page. Cancelling there stops " +
                        "future renewals — you keep access until the end of your current billing period.",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Refund Policy", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Refunds for Google Play subscription purchases are issued and reviewed by " +
                        "Google, not by CardioAI Live directly — this is standard for all Google Play " +
                        "subscriptions, not specific to this app. Use the button above to open your " +
                        "subscription on Google Play, where you can request a refund through Google's " +
                        "official process.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedButton(
                    onClick = { showDeleteConfirm1 = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete Account")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Deletes your login and personal profile. Your subscription is managed " +
                        "separately by Google Play — deleting your account does not automatically " +
                        "cancel it. Cancel above first if you don't want to be charged again.",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.outline,
                )
                deletionError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
                deletionSuccessMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }

    // Two-step confirmation for account deletion — irreversible actions
    // deserve more friction than a single tap, especially for a
    // healthcare app where "delete account" carries different weight
    // than in a typical consumer app.
    if (showDeleteConfirm1) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm1 = false },
            title = { Text("Delete your account?") },
            text = { Text("This permanently deletes your login and profile. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm1 = false
                    showDeleteConfirm2 = true
                }) { Text("Continue", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm1 = false }) { Text("Cancel") }
            },
        )
    }

    if (showDeleteConfirm2) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm2 = false },
            title = { Text("Are you absolutely sure?") },
            text = {
                Text(
                    "Your vitals history and clinical alerts will be retained as part of your " +
                        "medical record, consistent with healthcare record-keeping requirements. " +
                        "Everything else — your login, name, and profile — will be permanently deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm2 = false
                    isDeletingAccount = true
                    deletionError = null
                    scope.launch {
                        val result = container.authService.deleteAccount()
                        isDeletingAccount = false
                        result.onSuccess { note ->
                            deletionSuccessMessage = note
                            onAccountDeleted()
                        }
                        result.onFailure { e ->
                            deletionError = "Could not delete your account: ${e.message}. Please try again or contact support."
                        }
                    }
                }) { Text("Delete My Account", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm2 = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        Text(value, fontSize = 12.sp)
    }
}
