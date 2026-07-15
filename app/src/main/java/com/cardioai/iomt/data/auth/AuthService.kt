package com.cardioai.iomt.data.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.cardioai.iomt.data.models.UserInfo
import com.cardioai.iomt.data.network.ApiClient
import com.cardioai.iomt.data.network.ApiError
import com.cardioai.iomt.data.storage.SecureKey
import com.cardioai.iomt.data.storage.SecureStorage
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class AuthState {
    object SignedOut : AuthState()
    object Loading : AuthState()
    data class SignedIn(val user: UserInfo) : AuthState()
}

class AuthService(
    private val apiClient: ApiClient,
    private val secureStorage: SecureStorage,
    private val googleWebClientId: String,
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.SignedOut)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val currentUser: UserInfo?
        get() = (_authState.value as? AuthState.SignedIn)?.user

    init {
        restoreSession()
    }

    private fun restoreSession() {
        val token = secureStorage.read(SecureKey.ACCESS_TOKEN)
        val name = secureStorage.read(SecureKey.USER_NAME)
        val email = secureStorage.read(SecureKey.USER_EMAIL)
        val role = secureStorage.read(SecureKey.USER_ROLE)
        val patientId = secureStorage.read(SecureKey.PATIENT_ID)

        if (token != null && name != null && email != null && role != null) {
            _authState.value = AuthState.SignedIn(UserInfo(name = name, email = email, role = role, patientId = patientId))
        }
    }

    private fun persistSession(response: com.cardioai.iomt.data.models.LoginResponse) {
        secureStorage.save(SecureKey.ACCESS_TOKEN, response.accessToken)
        secureStorage.save(SecureKey.REFRESH_TOKEN, response.refreshToken)
        secureStorage.save(SecureKey.USER_NAME, response.user.name)
        secureStorage.save(SecureKey.USER_EMAIL, response.user.email)
        secureStorage.save(SecureKey.USER_ROLE, response.user.role)
        response.user.patientId?.let { secureStorage.save(SecureKey.PATIENT_ID, it) }
        _authState.value = AuthState.SignedIn(response.user)
    }

    // ── Email/password (clinical staff, and any account with a password) ──

    suspend fun login(email: String, password: String): Result<Unit> {
        _authState.value = AuthState.Loading
        return try {
            val response = apiClient.login(email, password)
            persistSession(response)
            Result.success(Unit)
        } catch (e: ApiError) {
            _authState.value = AuthState.SignedOut
            Result.failure(e)
        }
    }

    suspend fun signup(email: String, name: String, organization: String, password: String, role: String): Result<String> {
        return try {
            val response = apiClient.signup(email, name, organization, password, role)
            Result.success(response.message)
        } catch (e: ApiError) {
            Result.failure(e)
        }
    }

    // ── Google Sign-In (Android patient auth — the equivalent of Apple
    // Sign-In on iOS, since Android has no native platform sign-in tied to
    // this backend). Uses Credential Manager, the current recommended
    // Android API for Google Sign-In (replaces the older deprecated
    // GoogleSignInClient). ────────────────────────────────────────────────

    suspend fun signInWithGoogle(context: Context): Result<Unit> {
        _authState.value = AuthState.Loading
        return try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(googleWebClientId)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val credentialManager = CredentialManager.create(context)
            val result = credentialManager.getCredential(context, request)

            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
            val idToken = googleIdTokenCredential.idToken

            val response = apiClient.googleSignIn(
                idToken = idToken,
                firstName = googleIdTokenCredential.givenName ?: "",
                lastName = googleIdTokenCredential.familyName ?: "",
            )
            persistSession(response)
            Result.success(Unit)
        } catch (e: GetCredentialException) {
            _authState.value = AuthState.SignedOut
            Result.failure(Exception("Google sign-in failed: ${e.message}"))
        } catch (e: ApiError) {
            _authState.value = AuthState.SignedOut
            Result.failure(e)
        }
    }

    // ── Session lifecycle ────────────────────────────────────────────────

    suspend fun refreshTokenIfNeeded(): Boolean {
        val refreshToken = secureStorage.read(SecureKey.REFRESH_TOKEN) ?: return false
        return try {
            val response = apiClient.refresh(refreshToken)
            secureStorage.save(SecureKey.ACCESS_TOKEN, response.accessToken)
            secureStorage.save(SecureKey.REFRESH_TOKEN, response.refreshToken)
            true
        } catch (e: ApiError) {
            signOut()
            false
        }
    }

    suspend fun signOut() {
        try {
            apiClient.logout()
        } catch (e: Exception) {
            // Best-effort — local session must be cleared regardless of
            // whether the backend call succeeds (e.g. no network).
        }
        secureStorage.clearAll()
        _authState.value = AuthState.SignedOut
    }
}
