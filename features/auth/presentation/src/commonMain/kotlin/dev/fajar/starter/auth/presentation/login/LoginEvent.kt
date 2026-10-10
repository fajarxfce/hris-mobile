package dev.fajar.starter.auth.presentation.login

sealed interface LoginEvent {
    data object ProvidersRequested : LoginEvent

    data class SsoRequested(val providerId: String) : LoginEvent

    data object SsoCancellationRequested : LoginEvent

    data class EmailChanged(val value: String) : LoginEvent

    data class PasswordChanged(val value: String) : LoginEvent {
        override fun toString(): String = "PasswordChanged([redacted])"
    }

    data object PasswordVisibilityChanged : LoginEvent

    data object DemoAccountSelected : LoginEvent

    data object SignInRequested : LoginEvent
}
