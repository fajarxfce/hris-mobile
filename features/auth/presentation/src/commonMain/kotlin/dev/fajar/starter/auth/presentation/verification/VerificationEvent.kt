package dev.fajar.starter.auth.presentation.verification

sealed interface VerificationEvent {
    data object AccountRequested : VerificationEvent

    data object EnrollmentRequested : VerificationEvent

    data class CodeChanged(val value: String) : VerificationEvent {
        override fun toString() = "CodeChanged([redacted])"
    }

    data object RecoveryModeChanged : VerificationEvent

    data object EnrollmentConfirmed : VerificationEvent

    data object CodeSubmitted : VerificationEvent

    data object RecoveryCodesSaved : VerificationEvent

    data object SignOutRequested : VerificationEvent
}
