package dev.fajar.starter.people.presentation.profile

sealed interface ProfileEvent {
    data class DirectoryRequested(val after: String? = null) : ProfileEvent

    data class EmploymentSelected(val id: String) : ProfileEvent

    data object BackRequested : ProfileEvent

    data object VerificationRequested : ProfileEvent
}
