package com.cardioai.iomt.ui.paywall

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardioai.iomt.core.AppContainer
import kotlinx.coroutines.launch

@Composable
fun PaywallScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val productDetails by container.billingManager.productDetails.collectAsState()
    val selectedOffer by container.billingManager.selectedOffer.collectAsState()
    val isLoading by container.billingManager.isLoading.collectAsState()
    val error by container.billingManager.purchaseError.collectAsState()

    // Recompute whenever the selected offer changes, since these read
    // from BillingManager's current offer selection.
    val trialText = selectedOffer?.let { container.billingManager.trialOfferDescription() }
    val hasTrial = selectedOffer?.let { container.billingManager.hasTrialOffer() } ?: false

    val recurringPrice = selectedOffer
        ?.pricingPhases
        ?.pricingPhaseList
        ?.firstOrNull { it.priceAmountMicros > 0L }
        ?.formattedPrice
        ?: productDetails
            ?.subscriptionOfferDetails
            ?.firstOrNull()
            ?.pricingPhases
            ?.pricingPhaseList
            ?.firstOrNull()
            ?.formattedPrice
        ?: "$12.99"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Icon(
            Icons.Filled.Favorite, contentDescription = null,
            modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("CardioAI Live Premium", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Continuous cardiac monitoring, AI-powered alerts, and a direct line to your care team.",
            fontSize = 13.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(20.dp))
        FeatureRow("Pair unlimited BLE, Wear OS, and Fitbit devices")
        FeatureRow("Real-time 7-agent clinical AI analysis")
        FeatureRow("Direct alerts to your care team")
        FeatureRow("Full vitals history and reports")

        Spacer(Modifier.height(20.dp))
        if (isLoading) {
            CircularProgressIndicator()
        } else if (hasTrial && trialText != null) {
            Text(trialText, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3DDC97))
            Text("Cancel anytime in Google Play subscriptions", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            // Google Play requires the recurring price/terms to be visible
            // even when a free trial is offered — not just in the button.
            Text(
                "After your free trial, $recurringPrice will be charged monthly until cancelled.",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
            )
        } else {
            Text("$recurringPrice / month", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Cancel anytime in Google Play subscriptions", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                (context as? androidx.activity.ComponentActivity)?.let { activity ->
                    container.billingManager.launchPurchaseFlow(activity)
                }
            },
            enabled = productDetails != null && !isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (hasTrial) "Start Free Trial" else "Subscribe — $recurringPrice/month")
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { container.billingManager.refreshEntitlementStatus() }) {
            Text("Restore Purchases")
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = { scope.launch { container.authService.signOut() } }) {
            Text("Sign Out", color = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FeatureRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF3DDC97), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp)
    }
}
