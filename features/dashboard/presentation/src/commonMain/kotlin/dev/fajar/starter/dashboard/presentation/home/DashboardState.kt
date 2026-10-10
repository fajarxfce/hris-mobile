package dev.fajar.starter.dashboard.presentation.home

import dev.fajar.starter.dashboard.domain.entities.Dashboard
import dev.fajar.starter.identity.domain.entities.User

data class DashboardState(
    val tab: DashboardTab = DashboardTab.Overview,
    val user: User? = null,
    val dashboard: Dashboard? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val pageError: dev.fajar.starter.common.result.Failure? = null,
    val signingOut: Boolean = false,
    val savingAvailable: Boolean = false,
    val hasSavingPermission: Boolean = false,
    val accessError: dev.fajar.starter.common.result.Failure? = null,
    val flagError: dev.fajar.starter.common.result.Failure? = null,
    val error: dev.fajar.starter.common.result.Failure? = null,
)
