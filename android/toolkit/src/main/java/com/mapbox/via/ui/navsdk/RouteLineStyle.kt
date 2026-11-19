package com.mapbox.via.ui.navsdk

import com.mapbox.via.ui.ViaFrontendConstants

sealed interface RouteLineStyle {
    data class Solid(val width: Double, val casingWidth: Double) : RouteLineStyle

    data class Dotted(val diameter: Double, val spacing: Double) : RouteLineStyle

    companion object {
        val Driving: RouteLineStyle = Solid(
            width = ViaFrontendConstants.ROUTE_LINE_WIDTH,
            casingWidth = ViaFrontendConstants.ROUTE_LINE_CASING_WIDTH,
        )

        val Walking: RouteLineStyle = Dotted(
            diameter = ViaFrontendConstants.ROUTE_LINE_WALKING_DOT_DIAMETER,
            spacing = ViaFrontendConstants.ROUTE_LINE_WALKING_DOT_SPACING,
        )

        /** [profile] is a Directions profile: `walking` on Android, `mapbox/walking` on iOS. */
        fun forProfile(profile: String?): RouteLineStyle =
            if (profile?.substringAfterLast('/') == WALKING_PROFILE) Walking else Driving

        private const val WALKING_PROFILE = "walking"
    }
}
