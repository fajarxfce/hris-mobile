package dev.fajar.starter.home.presentation.services

import dev.fajar.starter.home.presentation.catalog.HomeService

sealed interface ServicesEvent {
    data class ServiceSelected(val service: HomeService) : ServicesEvent

    data object BackRequested : ServicesEvent
}
