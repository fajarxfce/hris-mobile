@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.auth.presentation.login

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.identity.domain.firebase.entities.FirebaseSignInInput
import dev.fajar.starter.identity.domain.firebase.usecases.*
import dev.fajar.starter.identity.domain.sso.usecases.*
import dev.fajar.starter.identity.domain.usecases.SignIn
import dev.fajar.starter.identity.domain.usecases.ValidateSignIn
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class LoginViewModel(
    private val signIn: SignIn,
    private val validate: ValidateSignIn,
    private val listProviders: ListSsoProviders,
    private val signInWithSso: SignInWithSso,
    private val firebaseOptions: LoadFirebaseSignInOptions,
    private val firebaseSignIn: SignInWithFirebase,
) : MviViewModel<LoginState, LoginEvent, Nothing>(LoginState()) {

    private var authorization: Job? = null
    private var discovery: Job? = null
    private var discoveryVersion = 0

    init {
        on<LoginEvent.ProvidersRequested>(::onProvidersRequested)
        on<LoginEvent.SsoRequested>(::onSsoRequested)
        on<LoginEvent.GoogleRequested>(::onGoogleRequested)
        on<LoginEvent.SsoCancellationRequested>(::onSsoCancellationRequested)
        on<LoginEvent.EmailChanged>(::onEmailChanged)
        on<LoginEvent.PasswordChanged>(::onPasswordChanged)
        on<LoginEvent.PasswordVisibilityChanged>(::onPasswordVisibilityChanged)
        on<LoginEvent.SignInRequested>(::onSignInRequested)
        onEvent(LoginEvent.ProvidersRequested)
    }

    private fun onProvidersRequested(event: LoginEvent.ProvidersRequested) {
        if (state.value.submitting) return
        discovery?.cancel()
        val version = ++discoveryVersion
        updateState {
            it.copy(
                options = null,
                optionsLoading = true,
                failure = null,
                providers = emptyList(),
                signInOperationId = null,
                googleOperationId = null,
            )
        }
        discovery =
            viewModelScope.launch {
                try {
                    val options = firebaseOptions()
                    currentCoroutineContext().ensureActive()
                    when (options) {
                        is AppResult.Success -> updateState { it.copy(options = options.value) }
                        is AppResult.Failed -> updateState { it.copy(failure = options.failure) }
                    }
                    when (val result = listProviders()) {
                        is AppResult.Success -> {
                            currentCoroutineContext().ensureActive()
                            updateState { it.copy(providers = result.value) }
                        }
                        is AppResult.Failed -> {
                            currentCoroutineContext().ensureActive()
                            updateState { it.copy(failure = it.failure ?: result.failure) }
                        }
                    }
                } finally {
                    if (version == discoveryVersion) updateState { it.copy(optionsLoading = false) }
                }
            }
    }

    private fun onGoogleRequested(event: LoginEvent.GoogleRequested) {
        val input = state.value
        if (input.submitting || input.optionsLoading || input.options?.google != true) return
        val operationId = input.googleOperationId ?: Uuid.random().toString()
        updateState {
            it.copy(
                submitting = true,
                pendingProviderId = "firebase.google",
                googleOperationId = operationId,
                failure = null,
                fieldErrors = emptyMap(),
            )
        }
        authorization =
            viewModelScope.launch {
                try {
                    val result = firebaseSignIn(FirebaseSignInInput.Google, operationId)
                    currentCoroutineContext().ensureActive()
                    when (result) {
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

    private fun onSsoRequested(event: LoginEvent.SsoRequested) {
        if (state.value.submitting || state.value.optionsLoading) return
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
                    signInOperationId = null,
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
                    signInOperationId = null,
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

    private fun onSignInRequested(event: LoginEvent.SignInRequested) {
        val input = state.value
        val options = input.options ?: return
        if (input.submitting || input.optionsLoading || !options.emailAvailable) return
        val errors = validate(input.email, input.password)
        updateState { it.copy(validated = true, fieldErrors = errors, failure = null) }
        if (errors.isNotEmpty()) return
        val operationId = input.signInOperationId ?: Uuid.random().toString()
        updateState { it.copy(submitting = true, signInOperationId = operationId) }
        authorization =
            viewModelScope.launch {
                try {
                    val result =
                        if (options.firebaseEmail)
                            firebaseSignIn(
                                FirebaseSignInInput.EmailPassword(input.email, input.password),
                                operationId,
                            )
                        else signIn(input.email, input.password, operationId)
                    currentCoroutineContext().ensureActive()
                    when (result) {
                        is AppResult.Success -> updateState { it.copy(password = "") }
                        is AppResult.Failed ->
                            updateState {
                                it.copy(
                                    failure = result.failure,
                                    fieldErrors =
                                        result.failure.violations.filterKeys { field ->
                                            field in setOf("email", "password")
                                        },
                                )
                            }
                    }
                } finally {
                    updateState { it.copy(submitting = false) }
                }
            }
    }
}
