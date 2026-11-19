package com.mapbox.via.ui.mapsession

import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.ui.navsdk.samePositionAs

sealed interface SearchScope {
    data object AlongRoute : SearchScope
    data object NearDestination : SearchScope
    data class InArea(val area: String) : SearchScope
    data object Nearby : SearchScope

    /** The label already says where, as a natural-language query does. */
    data object InLabel : SearchScope
}

/**
 * What the pins on the map were searched for. The design only specifies the
 * near-destination copy ("Gas stations near the destination"); the other scopes follow its pattern.
 */
data class SearchCaption(val label: String, val scope: SearchScope) {
    val text: String
        get() {
            val subject = label.replace('_', ' ').trim().replaceFirstChar { it.uppercaseChar() }
            return when (scope) {
                SearchScope.AlongRoute -> "$subject along the route"
                SearchScope.NearDestination -> "$subject near the destination"
                is SearchScope.InArea -> "$subject in ${scope.area}"
                SearchScope.Nearby -> "$subject nearby"
                SearchScope.InLabel -> subject
            }
        }

    companion object {
        fun around(
            label: String,
            inArea: String?,
            proximity: ViaPoint?,
            destination: ViaPoint?,
        ): SearchCaption {
            val area = inArea?.trim()?.takeIf(String::isNotEmpty)
            val scope = when {
                area != null -> SearchScope.InArea(area)
                proximity != null && destination != null && proximity.samePositionAs(destination) ->
                    SearchScope.NearDestination
                else -> SearchScope.Nearby
            }
            return SearchCaption(label, scope)
        }
    }
}
