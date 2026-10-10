package dev.fajar.starter.home.presentation.home

sealed interface HomeEvent {
    data class TabSelected(val tab: HomeTab) : HomeEvent

    data object CompanyChangeRequested : HomeEvent

    data object SettingsRequested : HomeEvent

    data object SignOutRequested : HomeEvent
}
