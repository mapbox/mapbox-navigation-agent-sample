package com.mapbox.via.ui.navsdk

object SearchPinLabel {
    private const val MaxDisplayCharacters = 42

    fun normalized(name: String?): String? = name
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { normalized ->
            if (normalized.length <= MaxDisplayCharacters) {
                normalized
            } else {
                normalized.take(MaxDisplayCharacters - 1).trimEnd() + "…"
            }
        }
}
