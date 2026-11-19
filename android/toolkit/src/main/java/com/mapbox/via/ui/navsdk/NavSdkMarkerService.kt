package com.mapbox.via.ui.navsdk

import com.mapbox.via.kit.models.ViaPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface NavSdkMarkerService {
    val markerClicks: Flow<ViaPoint> get() = emptyFlow()

    suspend fun addMapMarker(point: ViaPoint): Result<Unit> =
        Result.failure(UnsupportedOperationException("NavSdkMarkerService is not available on this platform"))

    suspend fun removeMapMarker(point: ViaPoint): Result<Unit> =
        Result.failure(UnsupportedOperationException("NavSdkMarkerService is not available on this platform"))
}
