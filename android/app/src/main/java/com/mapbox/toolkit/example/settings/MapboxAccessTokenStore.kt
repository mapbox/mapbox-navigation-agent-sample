package com.mapbox.toolkit.example.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mapbox public access token entered at runtime when none is baked into the build. */
class MapboxAccessTokenStore(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore =
        PreferenceDataStoreFactory.create {
            context.applicationContext.filesDir.resolve(DATASTORE_FILE_NAME)
        }

    private val _accessToken = MutableStateFlow("")
    val accessToken: StateFlow<String> = _accessToken.asStateFlow()

    init {
        scope.launch {
            _accessToken.value =
                dataStore.data
                    .map { it[ACCESS_TOKEN] ?: "" }
                    .first()
        }
    }

    fun setAccessToken(value: String) {
        val trimmed = value.trim()
        _accessToken.value = trimmed
        scope.launch {
            dataStore.edit { prefs ->
                if (trimmed.isBlank()) prefs.remove(ACCESS_TOKEN) else prefs[ACCESS_TOKEN] = trimmed
            }
        }
    }

    suspend fun awaitPersistedToken(): String =
        withContext(Dispatchers.IO) {
            dataStore.data.map { it[ACCESS_TOKEN] ?: "" }.first()
        }

    private companion object {
        const val DATASTORE_FILE_NAME = "mapbox_access_token.preferences_pb"
        val ACCESS_TOKEN = stringPreferencesKey("mapbox_access_token")
    }
}
