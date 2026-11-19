package com.mapbox.toolkit.example.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mapbox.toolkit.example.R
import com.mapbox.toolkit.example.settings.resolveMapboxAccessToken
import com.mapbox.toolkit.example.ui.onboarding.FirstLaunchNoticesDialog
import com.mapbox.toolkit.example.ui.settings.SettingsScreen
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mapbox.dash.sdk.DashNavigationFragment
import com.mapbox.toolkit.example.ToolkitApplication
import com.mapbox.toolkit.example.theme.MapboxAgentToolkitTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val application = context.applicationContext as ToolkitApplication
    val storedMapboxToken by application.mapboxAccessTokenStore.accessToken.collectAsStateWithLifecycle()
    val mapboxAccessToken = resolveMapboxAccessToken(storedMapboxToken)
    var dashReady by remember { mutableStateOf(application.isDashInitialized()) }
    var navigationFragment by remember { mutableStateOf<DashNavigationFragment?>(null) }
    val noticesAcknowledged by application.firstLaunchStore.noticesAcknowledged.collectAsStateWithLifecycle()
    var showingSettings by remember { mutableStateOf(false) }
    var permissionsGranted by remember { mutableStateOf(missingPermissions(context).isEmpty()) }

    LaunchedEffect(mapboxAccessToken) {
        dashReady = mapboxAccessToken.isNotBlank() && application.ensureDashInitialized(mapboxAccessToken)
    }

    if (showingSettings) {
        SettingsScreen(
            apiKeyStore = application.openAiApiKeyStore,
            mapboxAccessTokenStore = application.mapboxAccessTokenStore,
            onBack = { showingSettings = false },
        )
        return
    }

    if (!noticesAcknowledged) {
        FirstLaunchNoticesDialog(onAcknowledge = application.firstLaunchStore::acknowledgeNotices)
    }

    if (!dashReady) {
        MainScreenContent(
            state = MainUiState(),
            permissionsGranted = false,
            actionCalls = emptyFlow(),
            onStart = {},
            onStop = {},
            onErrorShown = {},
            onOpenSettings = { showingSettings = true },
            mapboxReady = false,
            modifier = modifier,
        )
        return
    }

    val navigationActions = application.navigationActions
    val viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            permissionsGranted = results.values.all { it }
            if (!permissionsGranted) viewModel.onPermissionsDenied()
        }

    LaunchedEffect(navigationFragment) {
        navigationFragment?.let { navigationActions.attach(it) }
    }

    LaunchedEffect(noticesAcknowledged) {
        if (!noticesAcknowledged) return@LaunchedEffect
        val missing = missingPermissions(context)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    LifecycleResumeEffect(Unit) {
        permissionsGranted = missingPermissions(context).isEmpty()
        onPauseOrDispose {}
    }

    MainScreenContent(
        state = state,
        permissionsGranted = permissionsGranted,
        actionCalls = viewModel.actionCalls,
        onStart = viewModel::start,
        onStop = viewModel::stop,
        onErrorShown = viewModel::onErrorShown,
        onOpenSettings = { showingSettings = true },
        mapboxReady = true,
        modifier = modifier,
        map = { NavigationMap(it, onFragmentChanged = { navigationFragment = it }) },
    )
}

private val SESSION_PERMISSIONS = listOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

private fun missingPermissions(context: Context) = SESSION_PERMISSIONS.filter {
    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreenContent(
    state: MainUiState,
    permissionsGranted: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onErrorShown: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    mapboxReady: Boolean = true,
    actionCalls: Flow<String> = emptyFlow(),
    map: @Composable (Modifier) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(actionCalls) {
        actionCalls.collectLatest { name ->
            snackbarHostState.showSnackbar("Tool: $name", duration = SnackbarDuration.Short)
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            onErrorShown()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_content_description))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (mapboxReady) {
                map(Modifier.fillMaxSize())
            } else {
                MapboxTokenPlaceholder(onOpenSettings, Modifier.fillMaxSize())
            }
        }

        val label = when (state.status) {
            LiveStatus.Idle -> "Start"
            LiveStatus.Connecting -> "Connecting…"
            LiveStatus.Connected -> "Listening"
            LiveStatus.Closing -> "Stopping…"
        }

        Button(
            onClick = if (state.status == LiveStatus.Idle) onStart else onStop,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            enabled = mapboxReady && permissionsGranted && state.status != LiveStatus.Closing,
        ) {
            Text(label)
        }
    }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MapboxAgentToolkitTheme {
        MainScreenContent(
            state = MainUiState(status = LiveStatus.Connected),
            permissionsGranted = true,
            onStart = {},
            onStop = {},
            onErrorShown = {},
            onOpenSettings = {},
            map = { Box(it.background(MaterialTheme.colorScheme.surfaceVariant)) },
        )
    }
}
