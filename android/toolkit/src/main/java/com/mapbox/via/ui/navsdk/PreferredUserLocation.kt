package com.mapbox.via.ui.navsdk

import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.isNullIsland

internal fun preferredUserLocation(
    simulationEnabled: Boolean,
    simulationAwareLocation: ViaPoint?,
    liveLocation: ViaPoint?,
): ViaPoint? {
    val validProviderLocation = simulationAwareLocation?.takeUnless { it.isNullIsland() }
    val validLiveLocation = liveLocation?.takeUnless { it.isNullIsland() }
    return if (simulationEnabled) {
        validProviderLocation
    } else {
        validLiveLocation ?: validProviderLocation
    }
}
