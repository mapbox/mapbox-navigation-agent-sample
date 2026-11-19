package com.mapbox.toolkit.example.ui.main

import android.view.View
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import com.mapbox.dash.sdk.DashNavigationFragment

private const val NAVIGATION_FRAGMENT_TAG = "DashNavigationFragment"

/**
 * Hosts the UX Framework's [DashNavigationFragment] inside Compose. The host activity must be a
 * [FragmentActivity] that handles configuration changes itself so rotation keeps the
 * same fragment and map. [onFragmentChanged] receives the fragment once it's added, and null once it's removed.
 */
@Composable
fun NavigationMap(modifier: Modifier = Modifier, onFragmentChanged: (DashNavigationFragment?) -> Unit = {}) {
    val fragmentManager = (LocalActivity.current as FragmentActivity).supportFragmentManager
    val containerId = remember { View.generateViewId() }
    val statusBarTop = WindowInsets.statusBars.getTop(LocalDensity.current)

    DisposableEffect(containerId) {
        onDispose {
            onFragmentChanged(null)
            fragmentManager.findFragmentByTag(NAVIGATION_FRAGMENT_TAG)?.let { fragment ->
                fragmentManager.beginTransaction()
                    .remove(fragment)
                    .commitAllowingStateLoss()
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context -> FragmentContainerView(context).apply { id = containerId } },
    ) { container ->
        val existing = fragmentManager.findFragmentByTag(NAVIGATION_FRAGMENT_TAG) as? DashNavigationFragment
        val fragment = existing?.takeIf { it.view?.parent === container }
            ?: DashNavigationFragment().also { newFragment ->
                fragmentManager.beginTransaction()
                    .apply { existing?.let(::remove) }
                    .add(containerId, newFragment, NAVIGATION_FRAGMENT_TAG)
                    .commitNow()
            }

        fragment.setSafeAreaPaddings(left = 0, top = statusBarTop, right = 0, bottom = 0)
        onFragmentChanged(fragment)
    }
}
