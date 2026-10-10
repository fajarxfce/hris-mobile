package dev.fajar.starter.common.time

import kotlin.time.TimeSource

/** Process-local elapsed time, unaffected by wall-clock adjustments. Never persist these values. */
fun interface ElapsedClock {
    fun milliseconds(): Long

    companion object {
        private val origin = TimeSource.Monotonic.markNow()
        val System = ElapsedClock { origin.elapsedNow().inWholeMilliseconds }
    }
}
