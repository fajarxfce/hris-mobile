package dev.fajar.starter.availability.domain.policy

import dev.fajar.starter.availability.domain.entities.*

/** Build floors are persistent; maintenance must expire even when the device stays offline. */
fun evaluateAvailability(policy: AppPolicy, currentBuild: Long, now: Long): AppAvailability {
    if (currentBuild < policy.minimumBuild)
        return AppAvailability.UpdateRequired(policy.minimumBuild)
    val maintenanceEnd =
        policy.maintenanceUntilEpochMillis?.coerceAtMost(
            policy.fetchedAtEpochMillis + MAX_MAINTENANCE_MILLIS
        )
    if (maintenanceEnd != null && maintenanceEnd > now)
        return AppAvailability.Maintenance(maintenanceEnd)
    if (currentBuild < policy.recommendedBuild && policy.dismissedBuild < policy.recommendedBuild)
        return AppAvailability.UpdateRecommended(policy.recommendedBuild)
    return AppAvailability.Available
}

const val MAX_MAINTENANCE_MILLIS: Long = 24 * 60 * 60 * 1000L
