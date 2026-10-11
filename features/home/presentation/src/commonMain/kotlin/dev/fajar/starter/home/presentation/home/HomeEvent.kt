package dev.fajar.starter.home.presentation.home

sealed interface HomeEvent {
    data class ServiceSelected(val service: HomeService) : HomeEvent

    data class TabSelected(val tab: HomeTab) : HomeEvent

    data object CompanyChangeRequested : HomeEvent

    data object SettingsRequested : HomeEvent

    data object SessionsRequested : HomeEvent

    data object SignOutRequested : HomeEvent
}
