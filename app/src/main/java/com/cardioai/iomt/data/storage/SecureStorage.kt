package com.cardioai.iomt.data.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android equivalent of KeychainService.swift. Uses EncryptedSharedPreferences
 * (AES-256-GCM, key managed by the Android Keystore) — the standard,
 * platform-recommended equivalent to iOS Keychain for storing tokens and
 * secrets outside of plain SharedPreferences.
 */
enum class SecureKey(val key: String) {
    ACCESS_TOKEN("com.cardioai.iomt.access_token"),
    REFRESH_TOKEN("com.cardioai.iomt.refresh_token"),
    PATIENT_ID("com.cardioai.iomt.patient_id"),
    DEVICE_ID("com.cardioai.iomt.device_id"),
    USER_ROLE("com.cardioai.iomt.user_role"),
    USER_NAME("com.cardioai.iomt.user_name"),
    USER_EMAIL("com.cardioai.iomt.user_email"),
    GOOGLE_USER_ID("com.cardioai.iomt.google_user_id"),
    HEALTH_CONNECT_DEVICE_ID("com.cardioai.iomt.health_connect_device_id"),
    GOOGLE_HEALTH_DEVICE_ID("com.cardioai.iomt.google_health_device_id"),
    GOOGLE_HEALTH_ACCESS_TOKEN("com.cardioai.iomt.google_health_access_token"),
    GOOGLE_HEALTH_REFRESH_TOKEN("com.cardioai.iomt.google_health_refresh_token"),
    HMAC_SHARED_SECRET("com.cardioai.iomt.shared_secret"),
    BACKEND_URL("com.cardioai.iomt.backend_url"),
}

class SecureStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "cardioai_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(key: SecureKey, value: String) {
        prefs.edit().putString(key.key, value).apply()
    }

    fun read(key: SecureKey): String? = prefs.getString(key.key, null)

    fun exists(key: SecureKey): Boolean = prefs.contains(key.key)

    fun delete(key: SecureKey) {
        prefs.edit().remove(key.key).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
