package com.mapbox.via.core.plugins.director.openai

/**
 * Caps backend function-call rounds so a looping tool cannot starve the turn.
 * Text omits tools on the next request; voice skips `response.create`.
 */
internal class ToolRoundLimiter(private val maxRounds: Int = DEFAULT_MAX_ROUNDS) {
    private var callRounds: Int = 0

    fun reset() {
        callRounds = 0
    }

    fun toolsAllowed(): Boolean = callRounds < maxRounds

    fun recordCallRound(): Boolean {
        callRounds++
        return callRounds <= maxRounds
    }

    companion object {
        const val DEFAULT_MAX_ROUNDS: Int = 6
    }
}
