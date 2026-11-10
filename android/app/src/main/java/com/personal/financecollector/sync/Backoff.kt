package com.personal.financecollector.sync

import kotlin.random.Random

/** Exponential backoff with jitter: the delay for attempt n is random in [half, full] of min(cap, base * 2^n). */
object Backoff {
    const val BASE_MS = 30_000L
    const val CAP_MS = 6 * 60 * 60 * 1000L

    fun delayMillis(attempts: Int, random: Random = Random.Default): Long {
        val exponent = attempts.coerceIn(0, 20)
        val full = minOf(CAP_MS, BASE_MS shl exponent)
        val half = full / 2
        return half + random.nextLong(half + 1)
    }
}
