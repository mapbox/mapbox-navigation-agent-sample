package com.mapbox.via.ui.navsdk

/**
 * Spoken and tool-facing error when a nearby search or route needs the user's
 * location and none is available (permission denied, Location Services off, or
 * iOS Simulator without Simulate Location).
 */
const val LOCATION_REQUIRED_MESSAGE: String =
    "This app needs access to your location to complete that request. " +
        "Turn on Location Services and try again."
