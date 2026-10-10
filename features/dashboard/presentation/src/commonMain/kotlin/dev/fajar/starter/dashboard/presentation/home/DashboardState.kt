package dev.fajar.starter.dashboard.presentation.home

import dev.fajar.starter.dashboard.domain.entities.Dashboard
import dev.fajar.starter.identity.domain.entities.User

data class DashboardState(
    val tab: DashboardTab = DashboardTab.Overview,
    val user: User? = null,
    val dashboard: Dashboard? = null,
    val loading: Boolean = true,
    val signingOut: Boolean = false,
    val error: String? = null,
)
