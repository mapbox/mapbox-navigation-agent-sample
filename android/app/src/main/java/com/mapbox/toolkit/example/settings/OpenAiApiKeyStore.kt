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

/** Persisted OpenAI API key entered at runtime (required for release builds). */
class OpenAiApiKeyStore(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore =
        PreferenceDataStoreFactory.create {
            context.applicationContext.filesDir.resolve(DATASTORE_FILE_NAME)
        }

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    init {
        scope.launch {
            _apiKey.value =
                dataStore.data
                    .map { it[API_KEY] ?: "" }
                    .first()
        }
    }

    fun setApiKey(value: String) {
        val trimmed = value.trim()
        _apiKey.value = trimmed
        scope.launch {
            dataStore.edit { prefs ->
                if (trimmed.isBlank()) prefs.remove(API_KEY) else prefs[API_KEY] = trimmed
            }
        }
    }

    suspend fun awaitPersistedKey(): String =
        withContext(Dispatchers.IO) {
            dataStore.data.map { it[API_KEY] ?: "" }.first()
        }

    private companion object {
        const val DATASTORE_FILE_NAME = "openai_api_key.preferences_pb"
        val API_KEY = stringPreferencesKey("openai_api_key")
    }
}
