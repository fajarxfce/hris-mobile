package dev.fajar.starter.dashboard.domain.entities

/** Stable operation identity survives cancellation, process death, and redelivery. */
data class ActivityChange(val operationId: String, val activityId: String, val saved: Boolean)
