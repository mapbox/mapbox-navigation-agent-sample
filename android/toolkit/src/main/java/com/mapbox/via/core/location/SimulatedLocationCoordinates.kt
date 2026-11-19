package com.mapbox.via.core.location

import com.mapbox.via.kit.models.ViaPoint

/**
 * Named cities offered in Settings as one-tap simulated-location presets.
 * Freeform `lat, lon` remains available for anywhere else.
 *
 * [Ashburn] stays hidden from the preset list until unlocked ([SimulatedLocationEasterEgg]).
 */
enum class SimulatedLocationPreset(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val isSecret: Boolean = false,
) {
    SanFrancisco(displayName = "SF", latitude = 37.7699, longitude = -122.3871),
    Seattle(displayName = "Seattle", latitude = 47.6062, longitude = -122.3321),
    WashingtonDc(displayName = "DC", latitude = 38.9072, longitude = -77.0369),
    Ashburn(
        displayName = "Ashburn",
        latitude = 39.0438,
        longitude = -77.4874,
        isSecret = true,
    ),
    ;

    val coordinateText: String
        get() = SimulatedLocationCoordinates.format(latitude, longitude)

    val point: ViaPoint
        get() = ViaPoint(latitude = latitude, longitude = longitude)
}

/**
 * Shared parse/default helpers for the simulated-location coordinate text field
 * (`"lat, lon"` format).
 */
object SimulatedLocationCoordinates {
    private const val MIN_LATITUDE = -90.0
    private const val MAX_LATITUDE = 90.0
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0

    val DEFAULT_PRESET: SimulatedLocationPreset = SimulatedLocationPreset.SanFrancisco
    val DEFAULT_TEXT: String = DEFAULT_PRESET.coordinateText
    val DEFAULT_POINT: ViaPoint = DEFAULT_PRESET.point

    @Suppress("ReturnCount")
    fun parse(text: String): ViaPoint? {
        val parts = text.split(",", limit = 2).map { it.trim() }
        if (parts.size != 2) return null
        val latitude = parts[0].toDoubleOrNull() ?: return null
        val longitude = parts[1].toDoubleOrNull() ?: return null
        if (latitude !in MIN_LATITUDE..MAX_LATITUDE || longitude !in MIN_LONGITUDE..MAX_LONGITUDE) return null
        return ViaPoint(latitude = latitude, longitude = longitude)
    }

    fun format(latitude: Double, longitude: Double): String = "$latitude, $longitude"

    /** Returns the preset whose stored text matches [text], if any. */
    fun matchingPreset(text: String): SimulatedLocationPreset? =
        SimulatedLocationPreset.entries.firstOrNull { it.coordinateText == text.trim() }

    fun visiblePresets(secretUnlocked: Boolean): List<SimulatedLocationPreset> =
        SimulatedLocationPreset.entries.filter { !it.isSecret || secretUnlocked }
}
