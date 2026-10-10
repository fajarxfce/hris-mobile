package dev.fajar.starter.dashboard.domain.config

import dev.fajar.starter.featureflags.domain.entities.BooleanFlag

object DashboardFlags {
    val SavedActivities = BooleanFlag("dashboard_saved_activities", defaultValue = true)
}
