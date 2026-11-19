package com.mapbox.via.core.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Named locations used by the debug test-location picker (map markers, search,
 * navigation) and by mock data providers (e.g. [com.mapbox.via.core.calendar.MockCalendarProvider])
 * that need to stay consistent with whichever location is selected for testing.
 */
enum class TestLocation(val displayName: String, val latitude: Double, val longitude: Double) {
    MountainView(displayName = "Mountain View, CA", latitude = 37.3861, longitude = -122.0839),
    Dc(displayName = "Dupont Circle, DC", latitude = 38.909444, longitude = -77.043333),
    MountPleasantDc(displayName = "Mount Pleasant, DC", latitude = 38.928694, longitude = -77.037333),
    MapboxDc(displayName = "Mapbox Office, DC", latitude = 38.906658, longitude = -77.044646),
    Sf(displayName = "Mission District, SF", latitude = 37.76, longitude = -122.42),
    MapboxSf(displayName = "Mapbox Office, SF", latitude = 37.793448, longitude = -122.399801),
    PikePlaceMarket(displayName = "Pike Place Market, Seattle", latitude = 47.609444, longitude = -122.341667),
    QueenAnne(displayName = "Queen Anne, Seattle", latitude = 47.628081, longitude = -122.353192),
    LakeForestPark(displayName = "Lake Forest Park, WA", latitude = 47.7738, longitude = -122.2711),
    LaurelHeights(displayName = "Laurel Heights, SF", latitude = 37.7865, longitude = -122.4475),
    CivicCenterSf(displayName = "Civic Center, SF", latitude = 37.774100, longitude = -122.420192),
    MissionBay(displayName = "Mission Bay, SF", latitude = 37.7712, longitude = -122.3891),
    McPhersonSquare(displayName = "McPherson Square, DC", latitude = 38.9047, longitude = -77.0342),
    MidtownManhattan(displayName = "Midtown Manhattan, NY", latitude = 40.7536, longitude = -73.9832),
    Scarsdale(displayName = "Scarsdale, NY", latitude = 40.9903, longitude = -73.7949),
    MidtownAtlanta(displayName = "Midtown Atlanta, GA", latitude = 33.7838, longitude = -84.3833),
    Munich(displayName = "Munich, Germany", latitude = 48.1351, longitude = 11.5820),
}

/**
 * Remembers which [TestLocation] is currently selected for debug/testing purposes.
 * In-memory only, matching [com.mapbox.via.core.calendar.CalendarSourceSelection].
 */
class TestLocationSelection(default: TestLocation = TestLocation.MountainView) {
    private val _selected = MutableStateFlow(default)
    val selected: StateFlow<TestLocation> = _selected.asStateFlow()

    fun select(location: TestLocation) {
        _selected.value = location
    }
}
