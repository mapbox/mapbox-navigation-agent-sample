package com.mapbox.via.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks what the active session needs from the map. Shells read [hasMapContent] to switch between
 * chat-only and map layouts, and [mapMountRequested] to decide whether a map has to exist at all.
 *
 * The two are separate because needing a map and showing one are different things: pinning
 * requires a live map, while the chat-first experience keeps search results in the timeline
 * rather than taking over the screen.
 */
class MapContentController {
    private val _hasMapContent = MutableStateFlow(false)
    val hasMapContent: StateFlow<Boolean> = _hasMapContent.asStateFlow()

    private val _mapMountRequested = MutableStateFlow(false)
    val mapMountRequested: StateFlow<Boolean> = _mapMountRequested.asStateFlow()

    /** Map-bearing content the user is meant to see: routes, destinations, guidance, an expanded widget. */
    fun markMapContentPresent() {
        _mapMountRequested.value = true
        _hasMapContent.value = true
    }

    /** A map has to exist for the nav SDK to work against, but nothing should change on screen. */
    fun requestMapMount() {
        _mapMountRequested.value = true
    }

    /** Back to chat while keeping the map alive, so expanding again does not rebuild it. */
    fun hideMapContent() {
        _hasMapContent.value = false
    }

    fun clearMapContent() {
        _hasMapContent.value = false
        _mapMountRequested.value = false
    }
}
