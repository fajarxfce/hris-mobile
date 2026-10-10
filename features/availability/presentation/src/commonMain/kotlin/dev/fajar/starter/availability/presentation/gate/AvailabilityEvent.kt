package dev.fajar.starter.availability.presentation.gate

sealed interface AvailabilityEvent {
    data object ObserveRequested : AvailabilityEvent

    data object RefreshRequested : AvailabilityEvent

    data object DismissRequested : AvailabilityEvent

    data object UpdateOpeningFailed : AvailabilityEvent

    data object UpdateRequested : AvailabilityEvent
}
