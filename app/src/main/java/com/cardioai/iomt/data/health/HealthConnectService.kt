package com.cardioai.iomt.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.permission.HealthPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Android equivalent of HealthKitService.swift — reads heart rate from
 * Health Connect, Android's unified health data store. Any app that writes
 * heart rate to Health Connect (Wear OS watches, Samsung Health, Fitbit's
 * own Android app if configured to sync there, etc.) becomes a data source
 * here, the same way any HealthKit-writing source works on iOS.
 *
 * Requires the Health Connect app to be installed (pre-installed on most
 * modern Android devices; available on Play Store otherwise) and the
 * READ_HEART_RATE permission granted at runtime.
 */
data class HealthConnectReading(val bpm: Double, val sourceApp: String, val timestamp: Instant)

class HealthConnectService(private val context: Context) {

    private val healthConnectClient: HealthConnectClient? by lazy {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else null
    }

    val isAvailable: Boolean
        get() = healthConnectClient != null

    private val requiredPermissions = setOf(HealthPermission.getReadPermission(HeartRateRecord::class))

    suspend fun hasPermission(): Boolean {
        val client = healthConnectClient ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(requiredPermissions)
    }

    /** Returns the permission set to request — pass to Health Connect's
     * permission request contract from the calling Activity/Composable. */
    fun permissionsToRequest(): Set<String> = requiredPermissions

    private var pollingJob: Job? = null

    /**
     * Starts polling Health Connect for new heart rate records every 5
     * minutes. Health Connect doesn't offer a live push/observer API for
     * third-party apps the way HealthKit's HKObserverQuery does, so
     * periodic polling is the correct approach here — this mirrors the
     * same 5-minute interval used for the Google Health/Fitbit polling
     * path for consistency.
     */
    fun startObservingHeartRate(scope: CoroutineScope, onSample: (HealthConnectReading) -> Unit) {
        stopObserving()
        val client = healthConnectClient ?: return
        pollingJob = scope.launch(Dispatchers.IO) {
            var lastReadTime = Instant.now().minus(15, ChronoUnit.MINUTES)
            while (isActive) {
                try {
                    val now = Instant.now()
                    val response = client.readRecords(
                        ReadRecordsRequest(
                            recordType = HeartRateRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(lastReadTime, now),
                        )
                    )
                    for (record in response.records) {
                        for (sample in record.samples) {
                            onSample(
                                HealthConnectReading(
                                    bpm = sample.beatsPerMinute.toDouble(),
                                    sourceApp = record.metadata.dataOrigin.packageName,
                                    timestamp = sample.time,
                                )
                            )
                        }
                    }
                    lastReadTime = now
                } catch (e: Exception) {
                    // Health Connect not installed, permission revoked mid-session,
                    // or a transient error — log and keep trying on the next tick
                    // rather than crashing the polling loop entirely.
                }
                delay(5 * 60 * 1000L)
            }
        }
    }

    fun stopObserving() {
        pollingJob?.cancel()
        pollingJob = null
    }
}
