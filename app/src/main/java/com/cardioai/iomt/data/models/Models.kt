package com.cardioai.iomt.data.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

// ============================================================================
// Resilient decoding note
// ============================================================================
// Every field below has a default value, and the shared Json instance (see
// NetworkModule.kt) is configured with coerceInputValues = true and
// ignoreUnknownKeys = true. This mirrors the exact fix applied to the iOS
// app's Models.swift: a missing or null field degrades to a safe default
// instead of failing the entire response decode. The backend is under
// active development — a screen going blank because one optional-in-
// practice field was absent is worse than showing slightly incomplete data.

enum class AlertLevel { critical, high, medium, low }

@Serializable
data class RPMAlert(
    @SerialName("alert_id") val id: String = "",
    @SerialName("patient_id") val patientId: String = "unknown",
    @SerialName("level") val alertLevelRaw: String = "low",
    @SerialName("description") val description: String = "",
    @SerialName("actions") val requiredActions: List<String> = emptyList(),
    @SerialName("notified") val notifiedParties: List<String> = emptyList(),
    @SerialName("timestamp") val timestamp: String = "",
) {
    val alertLevel: AlertLevel
        get() = runCatching { AlertLevel.valueOf(alertLevelRaw) }.getOrDefault(AlertLevel.low)
    val isCritical: Boolean get() = alertLevel == AlertLevel.critical
}

@Serializable
data class DeviceInfo(
    @SerialName("device_id") val id: String = "",
    @SerialName("patient_id") val patientId: String = "unknown",
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("data_count") val dataCount: Int = 0,
    @SerialName("last_data_at") val lastDataAt: String? = null,
    @SerialName("device_type") val deviceType: String? = null,
)

@Serializable
data class DeviceSummary(
    val total: Int = 0,
    val active: Int = 0,
    val inactive: Int = 0,
    val devices: List<DeviceInfo> = emptyList(),
)

@Serializable
data class BridgeStatus(
    @SerialName("bridge_id") val bridgeId: String = "",
    val timestamp: String = "",
    @SerialName("queue_depth") val queueDepth: Int = 0,
    @SerialName("agent_count") val agentCount: Int = 0,
    @SerialName("message_bus_total") val messageBusTotal: Int = 0,
    val devices: DeviceSummary = DeviceSummary(),
)

@Serializable
data class ClinicalReport(
    @SerialName("report_id") val id: String = "",
    @SerialName("alert_id") val alertId: String = "",
    @SerialName("patient_id") val patientId: String = "unknown",
    @SerialName("level") val levelRaw: String = "low",
    val summary: String = "",
    val actions: List<String> = emptyList(),
    val notified: List<String> = emptyList(),
    @SerialName("generated_at") val generatedAt: String = "",
) {
    val level: AlertLevel
        get() = runCatching { AlertLevel.valueOf(levelRaw) }.getOrDefault(AlertLevel.low)
}

@Serializable
data class UserInfo(
    val id: String = "",
    val name: String = "",
    val email: String = "",
    val role: String = "",
    @SerialName("patient_id") val patientId: String? = null,
)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Int = 900,
    val user: UserInfo = UserInfo(),
)

@Serializable
data class RefreshResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Int = 900,
)

@Serializable
data class DeviceRegistrationResponse(
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("patient_id") val patientId: String = "",
    val status: String = "",
    @SerialName("organization_configured") val organizationConfigured: Boolean = false,
)

@Serializable
data class ApiErrorBody(
    val error: String = "",
    val message: String = "",
)

@Serializable
data class SignupResponse(
    val id: String = "",
    val email: String = "",
    val name: String = "",
    val organization: String = "",
    val role: String = "",
    @SerialName("is_active") val isActive: Boolean = false,
    val message: String = "",
)

@Serializable
data class AdmissionRecord(
    @SerialName("patient_id") val patientId: String = "",
    @SerialName("patient_name") val patientName: String = "",
    val status: String = "unknown",
    val location: String = "",
    @SerialName("patient_class") val patientClass: String = "",
    @SerialName("last_event_type") val lastEventType: String = "",
    @SerialName("sending_facility") val sendingFacility: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class AdmissionSummary(
    @SerialName("total_known_patients") val totalKnownPatients: Int = 0,
    @SerialName("currently_admitted") val currentlyAdmitted: Int = 0,
    val patients: List<AdmissionRecord> = emptyList(),
)
