package dev.fajar.starter.home.presentation.home

import dev.fajar.starter.home.presentation.catalog.HomeService

sealed interface HomeEffect {
    data class OpenService(val service: HomeService) : HomeEffect

    data object OpenAllServices : HomeEffect

    data object OpenSettings : HomeEffect

    data object OpenDataSync : HomeEffect

    data object OpenPendingActions : HomeEffect

    data object OpenSessions : HomeEffect
}
