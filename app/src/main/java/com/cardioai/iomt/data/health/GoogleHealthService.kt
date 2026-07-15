package com.cardioai.iomt.data.health

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.cardioai.iomt.data.storage.SecureKey
import com.cardioai.iomt.data.storage.SecureStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

private const val TAG = "GoogleHealth"

// See GoogleHealthService.swift (iOS) for the full context on why this
// targets the Google Health API rather than the deprecated Fitbit Web
// API, the required Google Cloud Console setup, and the Restricted-scope
// production review requirement. Same platform, same setup steps — only
// the OAuth transport differs (Custom Tabs here vs ASWebAuthenticationSession
// on iOS), since Android has no direct equivalent of that iOS API.

data class GoogleHealthReading(val bpm: Double, val timestamp: Instant)

class GoogleHealthService(
    private val context: Context,
    private val secureStorage: SecureStorage,
) {
    // ── Fill these in from your Google Cloud Console OAuth client ────────
    // Create an OAuth Client ID of type "Android" (uses your app's SHA-1
    // signing certificate fingerprint + package name for verification —
    // no client secret needed, same public-client PKCE model as iOS).
    private val clientId = "YOUR_GOOGLE_OAUTH_CLIENT_ID"
    private val redirectUri = "cardioai://google-health-callback"
    private val scope = "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly"

    private val authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth"
    private val tokenUrl = "https://oauth2.googleapis.com/token"
    private val apiBaseUrl = "https://health.googleapis.com/v4"

    private val httpClient = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    var isConnected: Boolean = false
        private set
    var lastError: String? = null
        private set

    private var pkceVerifier: String = ""
    private var redirectDeferred: CompletableDeferred<Uri>? = null
    private var pollingJob: Job? = null

    init {
        isConnected = secureStorage.exists(SecureKey.GOOGLE_HEALTH_ACCESS_TOKEN)
    }

    /**
     * Launches the Custom Tab for Google's consent screen. The caller
     * (MainActivity) must forward the redirect intent to
     * [handleRedirect] when the browser redirects back to the app —
     * see AndroidManifest.xml's deep link intent-filter for
     * cardioai://google-health-callback.
     */
    suspend fun connect(): Boolean {
        if (clientId == "YOUR_GOOGLE_OAUTH_CLIENT_ID") {
            lastError = "Google Health integration is not configured yet — create an OAuth client in Google Cloud Console and set clientId in GoogleHealthService.kt"
            Log.w(TAG, lastError!!)
            return false
        }

        pkceVerifier = generateCodeVerifier()
        val challenge = codeChallenge(pkceVerifier)

        val authUri = Uri.parse(authorizeUrl).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", scope)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .build()

        redirectDeferred = CompletableDeferred()

        withContext(Dispatchers.Main) {
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.launchUrl(context, authUri)
        }

        val redirectResult = try {
            redirectDeferred!!.await()
        } catch (e: Exception) {
            lastError = "Google sign-in was cancelled or timed out"
            return false
        }

        val code = redirectResult.getQueryParameter("code")
        if (code == null) {
            lastError = "Google did not return an authorization code"
            return false
        }

        return try {
            exchangeCodeForToken(code)
            isConnected = true
            lastError = null
            true
        } catch (e: Exception) {
            lastError = "Token exchange failed: ${e.message}"
            false
        }
    }

    /** Call from MainActivity.onNewIntent() when the OAuth redirect intent arrives. */
    fun handleRedirect(uri: Uri) {
        redirectDeferred?.complete(uri)
    }

    fun disconnect() {
        stopPolling()
        secureStorage.delete(SecureKey.GOOGLE_HEALTH_ACCESS_TOKEN)
        secureStorage.delete(SecureKey.GOOGLE_HEALTH_REFRESH_TOKEN)
        isConnected = false
    }

    private suspend fun exchangeCodeForToken(code: String) = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", redirectUri)
            .add("code", code)
            .add("code_verifier", pkceVerifier)
            .build()

        val request = Request.Builder().url(tokenUrl).post(formBody).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: throw Exception("empty response")
            val parsed = json.parseToJsonElement(body).jsonObject
            val accessToken = parsed["access_token"]?.jsonPrimitive?.content ?: throw Exception("no access_token in response")
            secureStorage.save(SecureKey.GOOGLE_HEALTH_ACCESS_TOKEN, accessToken)
            parsed["refresh_token"]?.jsonPrimitive?.content?.let {
                secureStorage.save(SecureKey.GOOGLE_HEALTH_REFRESH_TOKEN, it)
            }
        }
    }

    private suspend fun refreshAccessToken(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = secureStorage.read(SecureKey.GOOGLE_HEALTH_REFRESH_TOKEN) ?: return@withContext false
        val formBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        val request = Request.Builder().url(tokenUrl).post(formBody).build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    disconnect()
                    return@withContext false
                }
                val body = response.body?.string() ?: return@withContext false
                val parsed = json.parseToJsonElement(body).jsonObject
                val accessToken = parsed["access_token"]?.jsonPrimitive?.content ?: return@withContext false
                secureStorage.save(SecureKey.GOOGLE_HEALTH_ACCESS_TOKEN, accessToken)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    // ── Polling for heart rate ──────────────────────────────────────────

    fun startPolling(scope: CoroutineScope, onSample: (GoogleHealthReading) -> Unit) {
        stopPolling()
        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                pollLatestHeartRate(onSample)
                delay(5 * 60 * 1000L)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun pollLatestHeartRate(onSample: (GoogleHealthReading) -> Unit) = withContext(Dispatchers.IO) {
        var accessToken = secureStorage.read(SecureKey.GOOGLE_HEALTH_ACCESS_TOKEN) ?: return@withContext

        // "heartRate" matches this API's camelCase data type naming
        // (consistent with other documented types). Verify against
        // Google's live schema if this ever 404s — see the iOS service's
        // module docstring for the same caveat about API stability.
        val url = Uri.parse("$apiBaseUrl/users/me/dataTypes/heartRate/dataPoints")
            .buildUpon().appendQueryParameter("page_size", "10").build()

        var request = Request.Builder().url(url.toString())
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Accept", "application/json")
            .build()

        try {
            var response = httpClient.newCall(request).execute()
            if (response.code == 401) {
                response.close()
                if (!refreshAccessToken()) return@withContext
                accessToken = secureStorage.read(SecureKey.GOOGLE_HEALTH_ACCESS_TOKEN) ?: return@withContext
                request = Request.Builder().url(url.toString())
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Accept", "application/json")
                    .build()
                response = httpClient.newCall(request).execute()
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "heart rate poll failed: HTTP ${resp.code}")
                    return@withContext
                }
                val body = resp.body?.string() ?: return@withContext
                parseLatestHeartRate(body)?.let(onSample)
            }
        } catch (e: Exception) {
            Log.w(TAG, "heart rate poll error: ${e.message}")
        }
    }

    /**
     * Defensive parsing — the API is new enough that the exact wrapper
     * key/field names are worth tolerating drift on, same rationale as
     * the iOS implementation's defensive parser.
     */
    private fun parseLatestHeartRate(body: String): GoogleHealthReading? {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val points = root["dataPoints"]?.jsonArray ?: return null
            val latest = points.lastOrNull()?.jsonObject ?: return null
            val hr = latest["heartRate"]?.jsonObject ?: return null
            val bpm = hr["beatsPerMinute"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
            GoogleHealthReading(bpm = bpm, timestamp = Instant.now())
        } catch (e: Exception) {
            Log.w(TAG, "could not find a heart rate value in response — API shape may have changed")
            null
        }
    }

    // ── PKCE helpers ─────────────────────────────────────────────────────

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
