package com.mapbox.toolkit.example.settings

import com.mapbox.toolkit.example.BuildConfig

/** Build-time token from `local.properties` wins over the user-entered runtime token. */
fun resolveMapboxAccessToken(storedToken: String): String =
    BuildConfig.MAPBOX_ACCESS_TOKEN.takeIf { it.isNotBlank() } ?: storedToken.trim()
