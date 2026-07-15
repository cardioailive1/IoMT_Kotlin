package com.cardioai.iomt.data.websocket

import android.util.Log
import com.cardioai.iomt.data.storage.SecureKey
import com.cardioai.iomt.data.storage.SecureStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TAG = "BridgeClient"

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Authenticating : ConnectionState()
    object Connected : ConnectionState()
    data class Reconnecting(val attempt: Int) : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()

    val isActive: Boolean get() = this is Connected

    /** Technical description — for Settings/troubleshooting only.
     * See patientFacingLabel for what patients should actually see. */
    val description: String
        get() = when (this) {
            is Disconnected -> "Disconnected"
            is Connecting -> "Connecting..."
            is Authenticating -> "Authenticating..."
            is Connected -> "Connected"
            is Reconnecting -> "Reconnecting (attempt $attempt)..."
            is Failed -> "Failed: $reason"
        }

    /**
     * Soft, non-technical label for patient-facing UI — mirrors the same
     * fix applied to ConnectionState.swift. Never surfaces raw internal
     * error strings like "HMAC secret not provisioned" to a patient's
     * dashboard; that's meaningful for troubleshooting/Settings only.
     * This is the real-time hardware bridge, which most patients never
     * provision at all — an unprovisioned state is not a "failure," it's
     * just an unused optional feature.
     */
    val patientFacingLabel: String
        get() = when (this) {
            is Disconnected -> "Not connected"
            is Connecting -> "Connecting..."
            is Authenticating -> "Connecting..."
            is Connected -> "Connected"
            is Reconnecting -> "Reconnecting..."
            is Failed -> "Not connected"
        }
}

class BridgeClient(private val secureStorage: SecureStorage) {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val httpClient = OkHttpClient()
    private var webSocket: WebSocket? = null
    private val json = Json { ignoreUnknownKeys = true }

    val isProvisioned: Boolean
        get() = secureStorage.exists(SecureKey.HMAC_SHARED_SECRET)

    fun connect(wsUrl: String) {
        val sharedSecret = secureStorage.read(SecureKey.HMAC_SHARED_SECRET)
        if (sharedSecret == null) {
            _connectionState.value = ConnectionState.Failed("HMAC secret not provisioned")
            return
        }

        _connectionState.value = ConnectionState.Connecting
        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.Authenticating
                sendHello(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text, sharedSecret)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}")
                _connectionState.value = ConnectionState.Failed(t.message ?: "connection failed")
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun sendHello(ws: WebSocket) {
        val hello = """{"type":"hello","payload":{"client_id":"cardioai-android","version":"1.0"}}"""
        ws.send(hello)
    }

    private fun handleMessage(ws: WebSocket, text: String, sharedSecret: String) {
        try {
            val parsed = json.parseToJsonElement(text).jsonObject
            when (parsed["type"]?.jsonPrimitive?.content) {
                "challenge" -> {
                    val challenge = parsed["payload"]?.jsonObject?.get("challenge")?.jsonPrimitive?.content ?: return
                    val signature = signChallenge(challenge, sharedSecret)
                    ws.send("""{"type":"challenge_resp","payload":{"challenge":"$challenge","signature":"$signature"}}""")
                }
                "auth_ok" -> {
                    _connectionState.value = ConnectionState.Connected
                }
                "auth_fail" -> {
                    _connectionState.value = ConnectionState.Failed("authentication rejected by server")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "malformed message: ${e.message}")
        }
    }

    /** HMAC-SHA256 signing, matching the exact scheme the backend/iOS use. */
    private fun signChallenge(challenge: String, sharedSecret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sharedSecret.toByteArray(), "HmacSHA256"))
        val digest = mac.doFinal(challenge.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
