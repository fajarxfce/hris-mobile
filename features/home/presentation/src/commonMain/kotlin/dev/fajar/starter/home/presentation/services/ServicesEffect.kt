package dev.fajar.starter.home.presentation.services

import dev.fajar.starter.home.presentation.catalog.HomeService

sealed interface ServicesEffect {
    data class OpenService(val service: HomeService) : ServicesEffect

    data object Back : ServicesEffect
}
