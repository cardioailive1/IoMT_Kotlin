package com.cardioai.iomt.data.network

import android.util.Log
import com.cardioai.iomt.data.models.*
import com.cardioai.iomt.data.storage.SecureKey
import com.cardioai.iomt.data.storage.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "ApiClient"

/**
 * Mirrors APIClient.swift's APIError enum, including the detailed decode
 * error messages fix — an error names the specific field/reason it failed
 * on, rather than a generic "could not read data" message that gives no
 * indication of what actually broke.
 */
sealed class ApiError(message: String) : Exception(message) {
    class InvalidUrl(path: String) : ApiError("Invalid URL for path: $path")
    class NetworkError(cause: Throwable) : ApiError("Network error: ${cause.message}")
    class HttpError(val statusCode: Int, val body: String) : ApiError("HTTP $statusCode: $body")
    class DecodingError(cause: Throwable) : ApiError("Decoding error: ${describe(cause)}")
    object Unauthorized : ApiError("Session expired — please sign in again")

    companion object {
        /**
         * Unlike a raw exception's message (which for kotlinx.serialization
         * failures can be a dense, hard-to-parse compiler-style message),
         * this extracts the most actionable summary available. Most
         * "missing field" cases are actually already handled gracefully by
         * coerceInputValues/ignoreUnknownKeys (see NetworkModule's Json
         * config) — this path is only reached for genuinely unparseable
         * responses (malformed JSON, completely wrong shape), so the raw
         * message is preserved rather than discarded, unlike the original
         * bug on iOS where the equivalent generic message hid the cause.
         */
        private fun describe(cause: Throwable): String {
            return when (cause) {
                is SerializationException -> "malformed response: ${cause.message}"
                else -> cause.message ?: cause.toString()
            }
        }
    }
}

@kotlinx.serialization.Serializable
private data class DeviceRegisterRequest(
    val device_id: String,
    val device_type: String,
    val patient_id: String,
    val device_name: String,
)

@kotlinx.serialization.Serializable
private data class LoginRequest(val email: String, val password: String)

@kotlinx.serialization.Serializable
private data class RefreshRequest(val refresh_token: String)

@kotlinx.serialization.Serializable
private data class LinkSubscriptionRequest(
    val platform: String,
    val transaction_id: String,
    val product_id: String,
)

@kotlinx.serialization.Serializable
private data class GoogleSigninRequest(
    val id_token: String,
    val first_name: String = "",
    val last_name: String = "",
)

@kotlinx.serialization.Serializable
private data class SignupRequest(
    val email: String, val name: String, val organization: String,
    val password: String, val role: String,
)

class ApiClient(private val secureStorage: SecureStorage) {

    var baseUrl: String
        get() = secureStorage.read(SecureKey.BACKEND_URL) ?: ""
        set(value) = secureStorage.save(SecureKey.BACKEND_URL, value.trimEnd('/'))

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ── Generic request core (mirrors APIClient.swift's request<T>()) ────

    private suspend inline fun <reified T> request(
        method: String,
        path: String,
        bodyJson: String? = null,
        auth: Boolean = true,
    ): T = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) throw ApiError.InvalidUrl(path)
        val url = "$baseUrl/$path"

        val requestBuilder = Request.Builder().url(url)
        if (auth) {
            val token = secureStorage.read(SecureKey.ACCESS_TOKEN)
            if (token != null) requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        val requestBody = (bodyJson ?: "{}").toRequestBody("application/json".toMediaType())

        when (method) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post(requestBody)
            "PATCH" -> requestBuilder.patch(requestBody)
            "DELETE" -> requestBuilder.delete()
            else -> throw IllegalArgumentException("Unsupported method: $method")
        }

        val response = try {
            httpClient.newCall(requestBuilder.build()).execute()
        } catch (e: IOException) {
            throw ApiError.NetworkError(e)
        }

        val responseBody = response.body?.string() ?: ""

        if (response.code == 401) {
            throw ApiError.Unauthorized
        }
        if (!response.isSuccessful) {
            Log.w(TAG, "HTTP ${response.code} for $method $path: ${responseBody.take(300)}")
            throw ApiError.HttpError(response.code, responseBody.take(500))
        }

        try {
            json.decodeFromString(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Decode error [${T::class.simpleName}]: ${e.message} | body: ${responseBody.take(500)}")
            throw ApiError.DecodingError(e)
        }
    }

    // ── Auth ─────────────────────────────────────────────────────────────

    suspend fun login(email: String, password: String): LoginResponse =
        request("POST", "auth/login", json.encodeToString(LoginRequest(email, password)), auth = false)

    suspend fun googleSignIn(idToken: String, firstName: String = "", lastName: String = ""): LoginResponse =
        request("POST", "auth/google", json.encodeToString(GoogleSigninRequest(idToken, firstName, lastName)), auth = false)

    suspend fun signup(email: String, name: String, organization: String, password: String, role: String): SignupResponse =
        request("POST", "auth/signup", json.encodeToString(SignupRequest(email, name, organization, password, role)), auth = false)

    suspend fun refresh(refreshToken: String): RefreshResponse =
        request("POST", "auth/refresh", json.encodeToString(RefreshRequest(refreshToken)), auth = false)

    suspend fun logout() {
        request<Map<String, String>>("POST", "auth/logout")
    }

    /** DELETE /account — self-service account deletion. See the backend's
     * db.delete_account() for what this actually does: credentials are
     * wiped unconditionally, but clinical data tied to this patient_id is
     * preserved for medical-record retention reasons, not silently
     * deleted. AccountDeletionResponse.clinicalDataNote carries that
     * explanation back to the UI. */
    suspend fun deleteAccount(): AccountDeletionResponse =
        request("DELETE", "account")

    /** POST /subscription/link — tells the backend which store purchase
     * belongs to the signed-in user, right after a client-confirmed
     * purchase. Without this, Google's RTDN webhook notifications (which
     * identify purchases by purchase token, not by our internal user_id)
     * have no way to know whose subscription status to update. */
    suspend fun linkSubscription(platform: String, transactionId: String, productId: String): Map<String, String> =
        request("POST", "subscription/link", json.encodeToString(
            LinkSubscriptionRequest(platform, transactionId, productId)
        ))

    // ── Devices ──────────────────────────────────────────────────────────

    suspend fun registerDevice(deviceId: String, deviceType: String, patientId: String, deviceName: String): DeviceRegistrationResponse =
        request("POST", "devices/register", json.encodeToString(DeviceRegisterRequest(deviceId, deviceType, patientId, deviceName)))

    suspend fun fetchDevices(): DeviceSummary = request("GET", "devices")

    suspend fun fetchStatus(): BridgeStatus = request("GET", "status")

    suspend fun fetchAlerts(): List<RPMAlert> = request("GET", "alerts")

    suspend fun fetchReports(): List<ClinicalReport> = request("GET", "reports")

    suspend fun fetchAdmissions(): AdmissionSummary = request("GET", "admissions")
}
