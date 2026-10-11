package dev.fajar.starter.home.presentation.home

sealed interface HomeEffect {
    data class OpenService(val service: HomeService) : HomeEffect

    data object OpenSettings : HomeEffect

    data object OpenSessions : HomeEffect
}
