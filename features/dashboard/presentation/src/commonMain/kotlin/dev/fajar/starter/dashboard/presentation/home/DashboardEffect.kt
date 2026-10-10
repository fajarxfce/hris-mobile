package dev.fajar.starter.dashboard.presentation.home

sealed interface DashboardEffect {
    data object OpenFiles : DashboardEffect

    data object OpenSettings : DashboardEffect

    data object OpenNotifications : DashboardEffect
}
