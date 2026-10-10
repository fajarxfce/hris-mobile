package dev.fajar.starter.dashboard.presentation.home

sealed interface DashboardEvent {
    data object FilesRequested : DashboardEvent

    data class TabSelected(val tab: DashboardTab) : DashboardEvent

    data class ActivitySavedChanged(val id: String, val saved: Boolean) : DashboardEvent

    data object NextPageRequested : DashboardEvent

    data object RefreshRequested : DashboardEvent

    data object SettingsRequested : DashboardEvent

    data object NotificationsRequested : DashboardEvent

    data object SignOutRequested : DashboardEvent
}
