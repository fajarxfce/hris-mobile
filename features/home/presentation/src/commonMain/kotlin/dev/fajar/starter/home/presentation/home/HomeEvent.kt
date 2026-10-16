package dev.fajar.starter.home.presentation.home

import dev.fajar.starter.home.presentation.catalog.HomeService

sealed interface HomeEvent {
    data class ServiceSelected(val service: HomeService) : HomeEvent

    data class TabSelected(val tab: HomeTab) : HomeEvent

    data object BackRequested : HomeEvent

    data object AllServicesRequested : HomeEvent

    data object CompanyChangeRequested : HomeEvent

    data object SettingsRequested : HomeEvent

    data object DataSyncRequested : HomeEvent

    data object SessionsRequested : HomeEvent

    data object SignOutRequested : HomeEvent
}
