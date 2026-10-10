package dev.fajar.starter.availability.presentation.gate

sealed interface AvailabilityEffect {
    data class OpenUpdate(val url: String) : AvailabilityEffect
}
