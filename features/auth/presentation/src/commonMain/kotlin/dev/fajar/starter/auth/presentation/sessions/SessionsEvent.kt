package dev.fajar.starter.auth.presentation.sessions

sealed interface SessionsEvent {
    data object RefreshRequested : SessionsEvent

    data class RevocationRequested(val id: String) : SessionsEvent

    data object RevocationConfirmed : SessionsEvent

    data object RevocationCancelled : SessionsEvent

    data object SignOutRequested : SessionsEvent

    data object BackRequested : SessionsEvent
}
