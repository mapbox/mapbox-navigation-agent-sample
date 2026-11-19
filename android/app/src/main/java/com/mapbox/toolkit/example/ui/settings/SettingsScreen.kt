package com.mapbox.toolkit.example.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.toolkit.example.BuildConfig
import com.mapbox.toolkit.example.R
import com.mapbox.toolkit.example.settings.MapboxAccessTokenStore
import com.mapbox.toolkit.example.settings.OpenAiApiKeyStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    apiKeyStore: OpenAiApiKeyStore,
    mapboxAccessTokenStore: MapboxAccessTokenStore,
    onBack: () -> Unit,
) {
    val savedMapboxToken by mapboxAccessTokenStore.accessToken.collectAsStateWithLifecycle()
    var draftMapboxToken by rememberSaveable(savedMapboxToken) { mutableStateOf(savedMapboxToken) }
    var showMapboxToken by rememberSaveable { mutableStateOf(false) }

    val savedKey by apiKeyStore.apiKey.collectAsStateWithLifecycle()
    var draftKey by rememberSaveable(savedKey) { mutableStateOf(savedKey) }
    var showKey by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.settings_mapbox_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_mapbox_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draftMapboxToken,
                onValueChange = { draftMapboxToken = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_mapbox_label)) },
                singleLine = true,
                visualTransformation =
                    if (showMapboxToken) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showMapboxToken = !showMapboxToken }) {
                        Icon(
                            if (showMapboxToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription =
                                stringResource(
                                    if (showMapboxToken) R.string.settings_hide_api_key else R.string.settings_show_api_key,
                                ),
                        )
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(
                onClick = { mapboxAccessTokenStore.setAccessToken(draftMapboxToken) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_save_mapbox_token))
            }

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.settings_openai_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_openai_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draftKey,
                onValueChange = { draftKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_openai_label)) },
                singleLine = true,
                visualTransformation =
                    if (showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription =
                                stringResource(
                                    if (showKey) R.string.settings_hide_api_key else R.string.settings_show_api_key,
                                ),
                        )
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(
                onClick = { apiKeyStore.setApiKey(draftKey) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_save_api_key))
            }

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.settings_location_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_location_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.settings_version_heading, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
