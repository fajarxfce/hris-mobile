package dev.fajar.starter.availability.presentation.gate

import dev.fajar.starter.availability.domain.entities.AppAvailability
import dev.fajar.starter.common.result.Failure

data class AvailabilityState(
    val loading: Boolean = true,
    val availability: AppAvailability = AppAvailability.Available,
    val refreshing: Boolean = false,
    val failure: Failure? = null,
    val updateUrl: String? = null,
)
