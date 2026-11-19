package com.mapbox.via.ui.mapsession

import com.mapbox.via.kit.models.ViaPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.runningFold

/**
 * Where the camera should move after a selection change, or null to leave it. A tap always moves,
 * even a re-tap of the selected pin after the map was dragged away from it. The first highlight of
 * a result set (the carousel settling on its opening card) is skipped: the search is still framing
 * every pin, and a concurrent snap to pin one would fight it.
 */
fun selectionCameraTarget(previous: MapSession, current: MapSession): ViaPoint? {
    val pin = current.selectedPin ?: return null
    return when {
        current.selectionTaps != previous.selectionTaps -> pin.point
        pin.id == previous.selectedPinId || previous.selectedPinId == null -> null
        else -> pin.point
    }
}

fun Flow<MapSession>.selectionCameraTargets(): Flow<ViaPoint> =
    runningFold<MapSession, Pair<MapSession, MapSession>?>(null) { acc, session ->
        (acc?.second ?: MapSession()) to session
    }
        .mapNotNull { pair -> pair?.let { (previous, current) -> selectionCameraTarget(previous, current) } }
