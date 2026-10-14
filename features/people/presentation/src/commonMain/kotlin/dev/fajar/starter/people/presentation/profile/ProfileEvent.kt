package dev.fajar.starter.people.presentation.profile

sealed interface ProfileEvent {
    data object Entered : ProfileEvent

    data object Stopped : ProfileEvent

    data object RefreshRequested : ProfileEvent

    data object EmploymentRequested : ProfileEvent

    data object EmploymentDismissed : ProfileEvent

    data class EmploymentSelected(val id: String) : ProfileEvent

    data object BackRequested : ProfileEvent

    data object VerificationRequested : ProfileEvent
}
