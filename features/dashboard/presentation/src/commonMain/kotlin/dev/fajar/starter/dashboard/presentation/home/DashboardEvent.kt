package dev.fajar.starter.dashboard.presentation.home

sealed interface DashboardEvent {
    data class TabSelected(val tab: DashboardTab) : DashboardEvent

    data object RefreshRequested : DashboardEvent

    data object SignOutRequested : DashboardEvent
}
