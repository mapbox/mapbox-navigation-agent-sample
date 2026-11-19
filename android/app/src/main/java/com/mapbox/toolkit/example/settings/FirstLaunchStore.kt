package com.mapbox.toolkit.example.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Whether the user has accepted first-launch legal and safety notices. */
class FirstLaunchStore(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore =
        PreferenceDataStoreFactory.create {
            context.applicationContext.filesDir.resolve(DATASTORE_FILE_NAME)
        }

    private val _noticesAcknowledged = MutableStateFlow(false)
    val noticesAcknowledged: StateFlow<Boolean> = _noticesAcknowledged.asStateFlow()

    init {
        scope.launch {
            _noticesAcknowledged.value =
                dataStore.data
                    .map { it[NOTICES_ACKNOWLEDGED] ?: false }
                    .first()
        }
    }

    fun acknowledgeNotices() {
        _noticesAcknowledged.value = true
        scope.launch {
            dataStore.edit { it[NOTICES_ACKNOWLEDGED] = true }
        }
    }

    private companion object {
        const val DATASTORE_FILE_NAME = "first_launch.preferences_pb"
        val NOTICES_ACKNOWLEDGED = booleanPreferencesKey("notices_acknowledged")
    }
}
