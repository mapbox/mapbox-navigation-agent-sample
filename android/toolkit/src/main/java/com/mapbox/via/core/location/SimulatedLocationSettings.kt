package com.mapbox.via.core.location

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mapbox.via.kit.models.ViaPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Persisted simulated-location toggle and coordinate text.
 * [effectivePoint] is the parsed coordinate, or SF default when the text is invalid.
 *
 * In-memory state is authoritative for the UI: we seed once from DataStore, then
 * persist writes in order under a [Mutex] without echoing persisted values back
 * over locally-authored updates.
 */
class SimulatedLocationSettings(
    private val dataStore: DataStore<Preferences>,
    private val defaultEnabled: Boolean,
    private val scope: CoroutineScope,
) {
    private val writeMutex = Mutex()
    private val initialized = CompletableDeferred<Unit>()

    @Volatile
    private var hasLocalMutation = false

    private val _enabled = MutableStateFlow(defaultEnabled)
    private val _coordinateText = MutableStateFlow(SimulatedLocationCoordinates.DEFAULT_TEXT)

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val coordinateText: StateFlow<String> = _coordinateText.asStateFlow()

    val isCoordinateValid: StateFlow<Boolean> = coordinateText
        .map { SimulatedLocationCoordinates.parse(it) != null }
        .stateIn(scope, SharingStarted.Eagerly, SimulatedLocationCoordinates.parse(_coordinateText.value) != null)

    val effectivePoint: StateFlow<ViaPoint> = coordinateText
        .map { text ->
            SimulatedLocationCoordinates.parse(text) ?: SimulatedLocationCoordinates.DEFAULT_POINT
        }
        .stateIn(scope, SharingStarted.Eagerly, SimulatedLocationCoordinates.DEFAULT_POINT)

    init {
        scope.launch {
            try {
                writeMutex.withLock {
                    if (hasLocalMutation) return@withLock
                    val prefs = dataStore.data.first()
                    if (hasLocalMutation) return@withLock
                    _enabled.value = prefs[ENABLED_KEY] ?: defaultEnabled
                    _coordinateText.value =
                        prefs[TEXT_KEY] ?: SimulatedLocationCoordinates.DEFAULT_TEXT
                }
            } finally {
                initialized.complete(Unit)
            }
        }
    }

    suspend fun awaitInitialized() {
        initialized.await()
    }

    fun setEnabled(enabled: Boolean) {
        hasLocalMutation = true
        _enabled.value = enabled
        scope.launch {
            writeMutex.withLock {
                dataStore.edit { prefs -> prefs[ENABLED_KEY] = enabled }
            }
        }
    }

    fun setCoordinateText(text: String) {
        hasLocalMutation = true
        _coordinateText.value = text
        scope.launch {
            writeMutex.withLock {
                dataStore.edit { prefs -> prefs[TEXT_KEY] = text }
            }
        }
    }

    fun setFromTestLocation(location: TestLocation) {
        setCoordinateText(
            SimulatedLocationCoordinates.format(location.latitude, location.longitude),
        )
    }

    fun setFromPreset(preset: SimulatedLocationPreset) {
        setCoordinateText(preset.coordinateText)
    }

    fun resetToDefault() {
        setFromPreset(SimulatedLocationCoordinates.DEFAULT_PRESET)
    }

    /**
     * Synchronous parse of the current text. Prefer this over [isCoordinateValid]/value /
     * [effectivePoint].value immediately after a write — those are [stateIn]-derived and can lag.
     */
    fun parsedCoordinateOrNull(): ViaPoint? =
        SimulatedLocationCoordinates.parse(_coordinateText.value)

    fun resolvedEffectivePoint(): ViaPoint =
        parsedCoordinateOrNull() ?: SimulatedLocationCoordinates.DEFAULT_POINT

    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("simulated_location_enabled")
        val TEXT_KEY = stringPreferencesKey("simulated_location_text")
    }
}
