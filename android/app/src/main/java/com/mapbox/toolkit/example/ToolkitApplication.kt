package com.mapbox.toolkit.example

import android.app.Application
import com.mapbox.dash.sdk.Dash
import com.mapbox.dash.sdk.config.api.debugSettings
import com.mapbox.toolkit.example.settings.FirstLaunchStore
import com.mapbox.toolkit.example.settings.MapboxAccessTokenStore
import com.mapbox.toolkit.example.settings.OpenAiApiKeyStore
import com.mapbox.toolkit.example.settings.resolveMapboxAccessToken
import com.mapbox.toolkit.navigation.NavigationActions

class ToolkitApplication : Application() {
  val openAiApiKeyStore by lazy { OpenAiApiKeyStore(this) }

  val mapboxAccessTokenStore by lazy { MapboxAccessTokenStore(this) }

  val firstLaunchStore by lazy { FirstLaunchStore(this) }

  private val dashLock = Any()

  @Volatile
  private var dashInitialized = false

  /** Navigation SDK actions; only valid after [ensureDashInitialized] succeeds. */
  val navigationActions: NavigationActions by lazy {
    check(dashInitialized) { "Call ensureDashInitialized before using navigationActions" }
    NavigationActions(this)
  }

  fun isDashInitialized(): Boolean = dashInitialized

  /** Initializes Dash once a non-blank Mapbox access token is available. */
  fun ensureDashInitialized(accessToken: String): Boolean {
    if (accessToken.isBlank()) return false
    if (dashInitialized) return true
    synchronized(dashLock) {
      if (dashInitialized) return true
      Dash.init(context = this, accessToken = accessToken) {
        // Adds a location simulation option to the UX Framework's settings, for trying the assistant away from the road.
        debugSettings {
          showSimulateLocationOption = true
        }
      }
      dashInitialized = true
      return true
    }
  }

  fun peekMapboxAccessToken(): String =
    resolveMapboxAccessToken(mapboxAccessTokenStore.accessToken.value)
}
