package com.mapbox.via.ui.location

import com.mapbox.via.kit.location.ViaLocationProvider
import com.mapbox.via.kit.logging.ViaLog
import com.mapbox.via.kit.context.ViaContextHolder
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.context.ViaUserContext
import com.mapbox.via.kit.models.isNullIsland
import com.mapbox.via.ui.navsdk.NavSdkService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Projects foreground location updates into [ViaContextHolder] for the lifetime
 * of the host process. Owns its own scope so context updates continue
 * regardless of which screen (if any) is currently mounted.
 */
class LocationContextSync(
    private val provider: ViaLocationProvider,
    private val holder: ViaContextHolder,
    private val navSdk: NavSdkService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    init {
        scope.launch { collectLocation() }
    }

    fun shutdown() {
        scope.cancel()
    }

    private suspend fun collectLocation() {
        val navSdkLocation = navSdk.userLocation
            .onStart { emit(null) }
        val providerLocation: Flow<ViaPoint?> = provider.observeLocation()
            .distinctUntilChanged()
            .retry { e ->
                ViaLog.w(e) { "Location stream error; resubscribing in ${RESUBSCRIBE_DELAY_MS}ms" }
                delay(RESUBSCRIBE_DELAY_MS.milliseconds)
                true
            }
        providerLocation
            .onStart { emit(null) }
            .combine(navSdkLocation) { providerPoint, navSdkPoint ->
                // Prefer Nav SDK, then GPS; skip (0, 0) so an unset fix is not a routing origin.
                listOf(navSdkPoint, providerPoint).firstOrNull { it != null && !it.isNullIsland() }
            }
            .distinctUntilChanged()
            .collect { publish(it) }
    }

    private fun publish(point: ViaPoint?) {
        ViaLog.d { "Location context: location updated" }
        holder.update { context ->
            val user = context.user ?: ViaUserContext()
            context.copy(user = user.copy(location = point))
        }
    }

    private companion object {
        const val RESUBSCRIBE_DELAY_MS = 15_000L
    }
}
