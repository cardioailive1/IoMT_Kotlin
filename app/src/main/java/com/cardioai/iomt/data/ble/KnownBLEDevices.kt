package com.cardioai.iomt.data.ble

/**
 * Kotlin port of KnownBLEDevices.swift — same catalog, same matching logic.
 * See the iOS file for sourcing notes; this list isn't exhaustive, anything
 * not matched here still works via the generic keyword fallback in
 * DevicePairingService.inferDeviceType().
 */
data class KnownBLEDeviceMatch(
    val deviceType: String,
    val displayLabel: String,
)

object KnownBLEDevices {

    private val catalog: List<Pair<List<String>, KnownBLEDeviceMatch>> = listOf(
        listOf("polar h10", "polar h9", "polar verity", "polar oh1") to
            KnownBLEDeviceMatch("ecg_monitor", "Polar Heart Rate Monitor"),
        listOf("wahoo tickr", "wahoo trackr") to
            KnownBLEDeviceMatch("ecg_monitor", "Wahoo Heart Rate Monitor"),
        listOf("garmin hrm", "garmin hr") to
            KnownBLEDeviceMatch("ecg_monitor", "Garmin Heart Rate Monitor"),
        listOf("coros") to
            KnownBLEDeviceMatch("ecg_monitor", "COROS Heart Rate Monitor"),
        listOf("coospo") to
            KnownBLEDeviceMatch("ecg_monitor", "CooSpo Heart Rate Monitor"),
        listOf("cycplus") to
            KnownBLEDeviceMatch("ecg_monitor", "CYCPLUS Heart Rate Monitor"),
        listOf("omron") to
            KnownBLEDeviceMatch("bp_monitor", "Omron Blood Pressure Monitor"),
        listOf("withings bpm", "withings bp", "withings core") to
            KnownBLEDeviceMatch("bp_monitor", "Withings Blood Pressure Monitor"),
        listOf("masimo") to
            KnownBLEDeviceMatch("pulse_oximeter", "Masimo Pulse Oximeter"),
        listOf("wellue", "viatom", "checkme") to
            KnownBLEDeviceMatch("pulse_oximeter", "Wellue/Viatom Pulse Oximeter"),
    )

    fun match(name: String): KnownBLEDeviceMatch? {
        val lowered = name.lowercase()
        for ((matchers, result) in catalog) {
            if (matchers.any { lowered.contains(it) }) return result
        }
        return null
    }
}
