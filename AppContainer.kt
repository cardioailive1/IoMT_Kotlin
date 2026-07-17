package com.cardioai.iomt.core

import android.content.Context
import com.cardioai.iomt.data.auth.AuthService
import com.cardioai.iomt.data.ble.DevicePairingService
import com.cardioai.iomt.data.billing.BillingManager
import com.cardioai.iomt.data.health.GoogleHealthService
import com.cardioai.iomt.data.health.HealthConnectService
import com.cardioai.iomt.data.network.ApiClient
import com.cardioai.iomt.data.storage.SecureStorage
import com.cardioai.iomt.data.websocket.BridgeClient

/**
 * Manual DI container — mirrors DependencyContainer.swift's pattern
 * (single shared instance, constructed once, wired together explicitly)
 * rather than introducing Hilt/Dagger, keeping the same lightweight
 * approach used on iOS for consistency across both platforms.
 */
class AppContainer(context: Context) {

    // ── Fill this in from your Google Cloud Console OAuth client — used
    // for Google Sign-In (Android patient auth). This is the "Web" client
    // ID paired with your Android client, per Credential Manager's
    // requirements for verifying ID tokens server-side. ─────────────────
    private val googleWebClientId = "YOUR_GOOGLE_WEB_CLIENT_ID"

    val secureStorage = SecureStorage(context)
    val apiClient = ApiClient(secureStorage)
    val bridgeClient = BridgeClient(secureStorage)
    val authService = AuthService(apiClient, secureStorage, googleWebClientId)
    val devicePairingService = DevicePairingService(context, apiClient, secureStorage)
    val healthConnectService = HealthConnectService(context)
    val googleHealthService = GoogleHealthService(context, secureStorage)

    // Subscription (gates the entire app — see BillingManager.kt for the
    // same "UI-only, not backend" caveat that applies on iOS too).
    val billingManager = BillingManager(context)

    companion object {
        @Volatile private var instance: AppContainer? = null

        fun getInstance(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}
