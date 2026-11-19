package com.mapbox.via.ui.navsdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.mapbox.via.ui.theme.LocalViaIsDark

/** A pin drawer for the resolved theme; a new instance (and bitmap cache) per light/dark flip. */
@Composable
internal fun rememberViaPinDrawer(): ViaPinDrawer {
    val context = LocalContext.current
    val isDark = LocalViaIsDark.current
    return remember(context, isDark) { ViaPinDrawer(context.applicationContext, isDark) }
}
