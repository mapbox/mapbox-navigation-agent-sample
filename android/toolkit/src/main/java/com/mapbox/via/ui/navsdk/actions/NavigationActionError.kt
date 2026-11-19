package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.ui.navsdk.NoActiveRouteException

internal fun Throwable.toNavigationActionMessage(
    noActiveRouteMessage: String,
    fallbackMessage: String,
): String = if (this is NoActiveRouteException) noActiveRouteMessage else fallbackMessage
