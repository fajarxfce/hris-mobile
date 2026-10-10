package dev.fajar.starter.auth.presentation.login

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.usecases.SignIn
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class LoginViewModel(private val signIn: SignIn) :
    MviViewModel<LoginState, LoginEvent, Nothing>(LoginState()) {

    init {
        on<LoginEvent.EmailChanged>(::onEmailChanged)
        on<LoginEvent.PasswordChanged>(::onPasswordChanged)
        on<LoginEvent.PasswordVisibilityChanged>(::onPasswordVisibilityChanged)
        on<LoginEvent.DemoAccountSelected>(::onDemoAccountSelected)
        on<LoginEvent.SignInRequested>(::onSignInRequested)
    }

    private fun onEmailChanged(event: LoginEvent.EmailChanged) {
        if (!state.value.submitting) updateState { it.copy(email = event.value, failure = null) }
    }

    private fun onPasswordChanged(event: LoginEvent.PasswordChanged) {
        if (!state.value.submitting) updateState { it.copy(password = event.value, failure = null) }
    }

    private fun onPasswordVisibilityChanged(event: LoginEvent.PasswordVisibilityChanged) {
        updateState { it.copy(passwordVisible = !it.passwordVisible) }
    }

    private fun onDemoAccountSelected(event: LoginEvent.DemoAccountSelected) {
        if (!state.value.submitting)
            updateState { LoginState(email = "demo@example.com", password = "Demo123!") }
    }

    private fun onSignInRequested(event: LoginEvent.SignInRequested) {
        val input = state.value
        if (input.submitting) return
        updateState { it.copy(submitting = true, failure = null) }
        viewModelScope.launch {
            when (val result = signIn(input.email, input.password)) {
                is AppResult.Success -> updateState { it.copy(submitting = false, password = "") }
                is AppResult.Failed ->
                    updateState { it.copy(submitting = false, failure = result.failure) }
            }
        }
    }
}
