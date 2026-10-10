package dev.fajar.starter.auth.presentation.login

sealed interface LoginEvent {
    data class EmailChanged(val value: String) : LoginEvent

    data class PasswordChanged(val value: String) : LoginEvent

    data object PasswordVisibilityChanged : LoginEvent

    data object DemoAccountSelected : LoginEvent

    data object SignInRequested : LoginEvent
}
