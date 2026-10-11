package dev.fajar.starter.home.presentation.home

sealed interface HomeEvent {
    data object InboxRequested : HomeEvent

    data class TabSelected(val tab: HomeTab) : HomeEvent

    data object CompanyChangeRequested : HomeEvent

    data object SettingsRequested : HomeEvent

    data object ProfileRequested : HomeEvent

    data object SessionsRequested : HomeEvent

    data object SignOutRequested : HomeEvent
}
