package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.logging.ViaLog
import com.mapbox.via.kit.search.DistantMatchCorroborator
import com.mapbox.via.kit.search.GENERIC_PLACE_WORDS
import com.mapbox.via.kit.search.SearchAnchor
import com.mapbox.via.kit.search.withDistanceFrom
import com.mapbox.via.kit.util.stripDiacritics
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.ui.navsdk.metersBetween
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private const val MIN_WORDS_TO_CORROBORATE = 2

private const val MIN_SIGNIFICANT_WORD_LENGTH = 3

// "top" only matters for deciding whether a query has anything distinctive to ask about (below),
// not for LocalNamesakeRecall's unrelated city-recall feature ("Top Pot Doughnuts").
private val GENERIC_CORROBORATION_WORDS = GENERIC_PLACE_WORDS + "top"

/** Letters Unicode does not decompose, so [stripDiacritics] leaves them intact. */
private val UNDECOMPOSABLE = mapOf(
    'ß' to "ss",
    'æ' to "ae",
    'œ' to "oe",
    'þ' to "th",
    'ø' to "o",
    'ł' to "l",
    'đ' to "d",
)

/**
 * Keeps the results within [windowMeters] of the first one [corroborator] judges to plausibly
 * answer [query]. Only asks [corroborator] when the query has a word outside
 * [GENERIC_CORROBORATION_WORDS] -- a query that's *entirely* generic ("the airport") has nothing
 * more specific to ask about, so it keeps the original behavior of trusting the only candidate
 * rather than guaranteeing a rejection (a strict predicate correctly judges
 * "the airport" has no real evidence for any specific airport, which is more honest but would
 * turn a best-effort guess into a hard failure for this one deliberately-accepted case).
 */
internal suspend fun List<ViaSearchResult>.clusterAroundDistantMatch(
    query: String,
    windowMeters: Double,
    corroborator: DistantMatchCorroborator,
    anchor: SearchAnchor?,
): List<ViaSearchResult> {
    val words = significantWords(query)
    val isAllGeneric = words.isNotEmpty() && (words - GENERIC_CORROBORATION_WORDS).isEmpty()
    val match = when {
        words.size < MIN_WORDS_TO_CORROBORATE || isAllGeneric -> firstOrNull()
        else -> firstPlausibleMatch(query, corroborator, anchor)
    } ?: return emptyList()
    return filter { metersBetween(match.coordinate, it.coordinate) <= windowMeters }
}

/**
 * Asks [corroborator] about every candidate concurrently (one Decisions API round-trip each,
 * otherwise this is `pool.size` sequential LLM calls on the voice path), then returns the first
 * match in the original order. A single candidate's failure degrades to "not a match" rather than
 * failing the whole search -- a Decisions API hiccup must not turn a best-effort search into
 * "No places found".
 */
private suspend fun List<ViaSearchResult>.firstPlausibleMatch(
    query: String,
    corroborator: DistantMatchCorroborator,
    anchor: SearchAnchor?,
): ViaSearchResult? = coroutineScope {
    val checks = map { candidate ->
        candidate to async { corroborator.plausiblyMatchesOrFalse(query, candidate, anchor) }
    }
    for ((candidate, matched) in checks) if (matched.await()) return@coroutineScope candidate
    null
}

@Suppress("TooGenericExceptionCaught")
private suspend fun DistantMatchCorroborator.plausiblyMatchesOrFalse(
    query: String,
    candidate: ViaSearchResult,
    anchor: SearchAnchor?,
): Boolean =
    try {
        plausiblyMatches(query, withDistanceFrom(candidate.candidateDescription(), anchor, candidate.coordinate))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ViaLog.w(e) { "distant match corroborator failed for '$query' vs '${candidate.name}'; treating as no match" }
        false
    }

/** Name plus every address field, in no particular display order -- just context for a judgment call. */
private fun ViaSearchResult.candidateDescription(): String {
    val address = address
    val streetLine = listOfNotNull(address?.houseNumber, address?.street).joinToString(" ").ifBlank { null }
    val parts = listOfNotNull(
        streetLine,
        address?.neighborhood,
        address?.locality,
        address?.place,
        address?.district,
        address?.region,
        address?.country,
        address?.postcode,
    )
    return if (parts.isEmpty()) name else "$name (${parts.joinToString(", ")})"
}

private fun significantWords(text: String): Set<String> =
    buildString {
        for (char in text.lowercase().stripDiacritics()) {
            append(if (char.isLetterOrDigit()) UNDECOMPOSABLE[char] ?: char.toString() else " ")
        }
    }
        .split(' ')
        .filter { it.length >= MIN_SIGNIFICANT_WORD_LENGTH }
        .toSet()
