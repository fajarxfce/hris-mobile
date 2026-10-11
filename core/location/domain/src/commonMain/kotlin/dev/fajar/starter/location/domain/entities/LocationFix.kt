package dev.fajar.starter.location.domain.entities

import kotlin.time.Instant

/** A single device observation. The consuming use case decides its suitability. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val observedAt: Instant,
    val mocked: Boolean,
) {
    override fun toString(): String = "LocationFix(redacted)"
}
