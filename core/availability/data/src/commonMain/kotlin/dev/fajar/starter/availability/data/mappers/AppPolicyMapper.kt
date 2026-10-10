package dev.fajar.starter.availability.data.mappers

import dev.fajar.starter.availability.domain.entities.AppPolicy
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.datastore.proto.UserPreferences

fun UserPreferences.toAppPolicy(platform: AppPlatform): AppPolicy {
    val prefix = "availability_${platform.key}_"
    val minimum = policyBuildNumber(feature_flag_values[prefix + "minimum_build"])
    val recommended =
        policyBuildNumber(feature_flag_values[prefix + "recommended_build"]).coerceAtLeast(minimum)
    val maintenanceEnabled =
        feature_flag_values["availability_maintenance"]?.let {
            requireNotNull(it.toBooleanStrictOrNull())
        } ?: false
    val until =
        if (maintenanceEnabled) {
            require(feature_flags_fetched_at > 0)
            requireNotNull(feature_flag_values["availability_maintenance_until"]?.toLongOrNull())
                .also { require(it > 0) }
        } else null
    return AppPolicy(
        minimum,
        recommended,
        until,
        feature_flags_fetched_at,
        dismissed_update_builds[platform.key] ?: 0,
    )
}

/** Validates one remote build-number field; absent fields disable the corresponding threshold. */
fun policyBuildNumber(value: String?): Long =
    value?.let {
        requireNotNull(it.toLongOrNull()).also { number -> require(number in 0..Int.MAX_VALUE) }
    } ?: 0
