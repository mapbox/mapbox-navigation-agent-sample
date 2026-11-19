package com.mapbox.toolkit.navigation

import com.mapbox.search.ResponseInfo
import com.mapbox.search.SearchCallback
import com.mapbox.search.common.AsyncOperationTask
import com.mapbox.search.result.SearchResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// Shared plumbing for the Search SDK backed repositories. The Search SDK reads the access token the app gave to
// `Dash.init`, so nothing here needs its own.

/** Runs a callback based Search SDK request as a suspend call, cancelling the request with the coroutine. */
internal suspend fun awaitSearch(request: (SearchCallback) -> AsyncOperationTask): Result<List<SearchResult>> =
    suspendCancellableCoroutine { continuation ->
        val task = request(
            object : SearchCallback {
                override fun onResults(results: List<SearchResult>, responseInfo: ResponseInfo) {
                    if (continuation.isActive) continuation.resume(Result.success(results))
                }

                override fun onError(e: Exception) {
                    if (continuation.isActive) continuation.resume(Result.failure(e))
                }
            },
        )
        continuation.invokeOnCancellation { task.cancel() }
    }
