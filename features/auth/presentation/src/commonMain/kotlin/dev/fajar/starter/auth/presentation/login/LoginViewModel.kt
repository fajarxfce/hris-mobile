package dev.fajar.starter.auth.presentation.login

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.identity.domain.sso.usecases.*
import dev.fajar.starter.identity.domain.usecases.SignIn
import dev.fajar.starter.identity.domain.usecases.ValidateSignIn
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class LoginViewModel(
    private val signIn: SignIn,
    private val validate: ValidateSignIn,
    private val listProviders: ListSsoProviders,
    private val signInWithSso: SignInWithSso,
) : MviViewModel<LoginState, LoginEvent, Nothing>(LoginState()) {

    private var authorization: Job? = null

    init {
        on<LoginEvent.ProvidersRequested>(::onProvidersRequested)
        on<LoginEvent.SsoRequested>(::onSsoRequested)
        on<LoginEvent.SsoCancellationRequested>(::onSsoCancellationRequested)
        on<LoginEvent.EmailChanged>(::onEmailChanged)
        on<LoginEvent.PasswordChanged>(::onPasswordChanged)
        on<LoginEvent.PasswordVisibilityChanged>(::onPasswordVisibilityChanged)
        on<LoginEvent.DemoAccountSelected>(::onDemoAccountSelected)
        on<LoginEvent.SignInRequested>(::onSignInRequested)
        onEvent(LoginEvent.ProvidersRequested)
    }

    private fun onProvidersRequested(event: LoginEvent.ProvidersRequested) {
        viewModelScope.launch {
            when (val result = listProviders()) {
                is AppResult.Success -> updateState { it.copy(providers = result.value) }
                is AppResult.Failed -> updateState { it.copy(failure = result.failure) }
            }
        }
    }

    private fun onSsoRequested(event: LoginEvent.SsoRequested) {
        if (state.value.submitting) return
        updateState {
            it.copy(
                submitting = true,
                pendingProviderId = event.providerId,
                failure = null,
                fieldErrors = emptyMap(),
            )
        }
        authorization =
            viewModelScope.launch {
                try {
                    when (val result = signInWithSso(event.providerId)) {
                        is AppResult.Success -> updateState { it.copy(password = "") }
                        is AppResult.Failed ->
                            updateState {
                                it.copy(
                                    failure =
                                        result.failure.takeUnless { failure ->
                                            failure.kind == FailureKind.Cancelled
                                        }
                                )
                            }
                    }
                } finally {
                    updateState { it.copy(submitting = false, pendingProviderId = null) }
                }
            }
    }

    private fun onSsoCancellationRequested(event: LoginEvent.SsoCancellationRequested) {
        authorization?.cancel()
    }

    private fun onEmailChanged(event: LoginEvent.EmailChanged) {
        if (!state.value.submitting)
            updateState {
                val errors = it.fieldErrors - "email"
                it.copy(
                    email = event.value,
                    failure = null,
                    fieldErrors =
                        errors +
                            if (it.validated)
                                validate(event.value, it.password).filterKeys { key ->
                                    key == "email"
                                }
                            else emptyMap(),
                )
            }
    }

    private fun onPasswordChanged(event: LoginEvent.PasswordChanged) {
        if (!state.value.submitting)
            updateState {
                val errors = it.fieldErrors - "password"
                it.copy(
                    password = event.value,
                    failure = null,
                    fieldErrors =
                        errors +
                            if (it.validated)
                                validate(it.email, event.value).filterKeys { key ->
                                    key == "password"
                                }
                            else emptyMap(),
                )
            }
    }

    private fun onPasswordVisibilityChanged(event: LoginEvent.PasswordVisibilityChanged) {
        updateState { it.copy(passwordVisible = !it.passwordVisible) }
    }

    private fun onDemoAccountSelected(event: LoginEvent.DemoAccountSelected) {
        if (!state.value.submitting)
            updateState {
                it.copy(
                    email = "demo@example.com",
                    password = "Demo123!",
                    failure = null,
                    fieldErrors = emptyMap(),
                    validated = false,
                )
            }
    }

    private fun onSignInRequested(event: LoginEvent.SignInRequested) {
        val input = state.value
        if (input.submitting) return
        val errors = validate(input.email, input.password)
        updateState { it.copy(validated = true, fieldErrors = errors, failure = null) }
        if (errors.isNotEmpty()) return
        updateState { it.copy(submitting = true) }
        viewModelScope.launch {
            when (val result = signIn(input.email, input.password)) {
                is AppResult.Success -> updateState { it.copy(submitting = false, password = "") }
                is AppResult.Failed ->
                    updateState {
                        it.copy(
                            submitting = false,
                            failure = result.failure,
                            fieldErrors =
                                result.failure.violations.filterKeys { field ->
                                    field in setOf("email", "password")
                                },
                        )
                    }
            }
        }
    }
}
