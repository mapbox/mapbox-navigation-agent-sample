package com.mapbox.via.ui.mapsession

import com.mapbox.via.kit.conversation.ConversationContinuityWriter
import com.mapbox.via.kit.models.ViaSearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One ordered queue for trip continuity. [onTripChanged] only enqueues, so the session can
 * publish the next state without waiting. [recordDestination] enqueues and then waits, which
 * keeps a destination committed later in the same turn from landing before a clear already
 * queued here. Call it from outside [scope]: this dispatcher runs one task at a time, and
 * waiting on it never starts the write.
 */
class TripContinuitySync(
    private val continuity: ConversationContinuityWriter,
    private val scope: CoroutineScope,
) {
    fun onTripChanged(session: MapSession, routeCleared: Boolean) {
        scope.launch {
            if (routeCleared) {
                continuity.clearRoute()
            } else {
                continuity.recordWaypoints(session.addedStops.map { stop -> stop.point to stop.mapboxId })
            }
        }
    }

    suspend fun recordDestination(result: ViaSearchResult) {
        scope.launch { continuity.recordDestination(result) }.join()
    }

    fun shutdown() {
        scope.cancel()
    }
}
