@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.auth.presentation.verification

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.usecases.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class VerificationViewModel(
    @InjectedParam private val sessionId: String,
    private val loadAccount: LoadAccount,
    private val acquireTokens: AcquireSessionTokens,
    private val beginEnrollment: BeginMfaEnrollment,
    private val confirmEnrollment: ConfirmMfaEnrollment,
    private val verifyMfa: VerifyMfa,
    private val signOut: SignOut,
) : MviViewModel<VerificationState, VerificationEvent, VerificationEffect>(VerificationState()) {
    init {
        on<VerificationEvent.AccountRequested>(::onAccountRequested)
        on<VerificationEvent.EnrollmentRequested>(::onEnrollmentRequested)
        on<VerificationEvent.CodeChanged>(::onCodeChanged)
        on<VerificationEvent.RecoveryModeChanged>(::onRecoveryModeChanged)
        on<VerificationEvent.EnrollmentConfirmed>(::onEnrollmentConfirmed)
        on<VerificationEvent.CodeSubmitted>(::onCodeSubmitted)
        on<VerificationEvent.RecoveryCodesSaved>(::onRecoveryCodesSaved)
        on<VerificationEvent.SignOutRequested>(::onSignOutRequested)
        onEvent(VerificationEvent.AccountRequested)
    }

    private fun onAccountRequested(event: VerificationEvent.AccountRequested) {
        if (state.value.working || state.value.step != VerificationStep.Loading) return
        updateState { it.copy(working = true, failure = null) }
        viewModelScope.launch {
            when (val result = loadAccount(sessionId)) {
                is AppResult.Failed ->
                    updateState { it.copy(working = false, failure = result.failure) }
                is AppResult.Success -> {
                    val assurance = result.value.assurance
                    val step =
                        if (!assurance.required || assurance.verified) VerificationStep.Verified
                        else if (result.value.mfaConfigured) VerificationStep.Verify
                        else if (assurance.setupAvailable) VerificationStep.Setup
                        else VerificationStep.Loading
                    updateState {
                        it.copy(
                            working = false,
                            step = step,
                            failure =
                                if (step == VerificationStep.Loading)
                                    Failure(
                                        FailureKind.Unavailable,
                                        "Authenticator setup is unavailable.",
                                        code = "mfa_unavailable",
                                    )
                                else null,
                        )
                    }
                    if (step == VerificationStep.Verified) emitEffect(VerificationEffect.Verified)
                }
            }
        }
    }

    private fun onEnrollmentRequested(event: VerificationEvent.EnrollmentRequested) {
        val current = state.value
        if (current.working || current.step != VerificationStep.Setup || current.enrollment != null)
            return
        val operationId = current.enrollmentOperationId ?: Uuid.random().toString()
        updateState { it.copy(working = true, failure = null, enrollmentOperationId = operationId) }
        viewModelScope.launch {
            when (val tokens = acquireTokens(sessionId)) {
                is AppResult.Failed -> {
                    updateState { it.copy(working = false, failure = tokens.failure) }
                    return@launch
                }
                is AppResult.Success ->
                    if (tokens.value == null) {
                        updateState {
                            it.copy(
                                working = false,
                                failure =
                                    Failure(
                                        FailureKind.Unauthorized,
                                        "Sign in again.",
                                        code = "client.session_changed",
                                    ),
                            )
                        }
                        return@launch
                    }
            }
            when (val result = beginEnrollment(sessionId, operationId)) {
                is AppResult.Failed ->
                    updateState { it.copy(working = false, failure = result.failure) }
                is AppResult.Success ->
                    updateState { it.copy(working = false, enrollment = result.value) }
            }
        }
    }

    private fun onCodeChanged(event: VerificationEvent.CodeChanged) {
        if (!state.value.working && !state.value.uncertain)
            updateState { it.copy(code = event.value.take(64), failure = null) }
    }

    private fun onRecoveryModeChanged(event: VerificationEvent.RecoveryModeChanged) {
        if (!state.value.working && !state.value.uncertain)
            updateState { it.copy(recovery = !it.recovery, code = "", failure = null) }
    }

    private fun onEnrollmentConfirmed(event: VerificationEvent.EnrollmentConfirmed) {
        val input = state.value
        val enrollment = input.enrollment ?: return
        if (input.working || input.uncertain || input.step != VerificationStep.Setup) return
        updateState { it.copy(working = true, failure = null) }
        viewModelScope.launch {
            when (val tokens = acquireTokens(sessionId)) {
                is AppResult.Failed -> {
                    updateState { it.copy(working = false, failure = tokens.failure) }
                    return@launch
                }
                is AppResult.Success ->
                    if (tokens.value == null) {
                        updateState {
                            it.copy(
                                working = false,
                                failure =
                                    Failure(
                                        FailureKind.Unauthorized,
                                        "Sign in again.",
                                        code = "client.session_changed",
                                    ),
                            )
                        }
                        return@launch
                    }
            }
            when (
                val result =
                    confirmEnrollment(
                        sessionId,
                        Uuid.random().toString(),
                        enrollment.operationId,
                        input.code,
                    )
            ) {
                is AppResult.Failed ->
                    updateState {
                        it.copy(
                            working = false,
                            failure = result.failure,
                            uncertain = verificationNeedsNewSignIn(result.failure),
                            enrollment =
                                it.enrollment.takeUnless {
                                    result.failure.code == "mfa_enrollment_expired"
                                },
                            enrollmentOperationId =
                                it.enrollmentOperationId.takeUnless {
                                    result.failure.code == "mfa_enrollment_expired"
                                },
                        )
                    }
                is AppResult.Success ->
                    updateState {
                        it.copy(
                            working = false,
                            step = VerificationStep.RecoveryCodes,
                            code = "",
                            enrollment = null,
                            recoveryCodes = result.value,
                        )
                    }
            }
        }
    }

    private fun onCodeSubmitted(event: VerificationEvent.CodeSubmitted) {
        val input = state.value
        if (input.working || input.uncertain || input.step != VerificationStep.Verify) return
        updateState { it.copy(working = true, failure = null) }
        viewModelScope.launch {
            when (val tokens = acquireTokens(sessionId)) {
                is AppResult.Failed -> {
                    updateState { it.copy(working = false, failure = tokens.failure) }
                    return@launch
                }
                is AppResult.Success ->
                    if (tokens.value == null) {
                        updateState {
                            it.copy(
                                working = false,
                                failure =
                                    Failure(
                                        FailureKind.Unauthorized,
                                        "Sign in again.",
                                        code = "client.session_changed",
                                    ),
                            )
                        }
                        return@launch
                    }
            }
            when (
                val result =
                    verifyMfa(sessionId, Uuid.random().toString(), input.code, input.recovery)
            ) {
                is AppResult.Failed ->
                    updateState {
                        it.copy(
                            working = false,
                            failure = result.failure,
                            uncertain = verificationNeedsNewSignIn(result.failure),
                        )
                    }
                is AppResult.Success -> {
                    updateState {
                        it.copy(working = false, step = VerificationStep.Verified, code = "")
                    }
                    emitEffect(VerificationEffect.Verified)
                }
            }
        }
    }

    private fun onRecoveryCodesSaved(event: VerificationEvent.RecoveryCodesSaved) {
        if (state.value.step != VerificationStep.RecoveryCodes) return
        updateState { it.copy(step = VerificationStep.Verified, recoveryCodes = emptyList()) }
        viewModelScope.launch { emitEffect(VerificationEffect.Verified) }
    }

    private fun onSignOutRequested(event: VerificationEvent.SignOutRequested) {
        if (state.value.working) return
        updateState { it.copy(working = true) }
        viewModelScope.launch {
            when (val result = signOut()) {
                is AppResult.Failed ->
                    updateState { it.copy(working = false, failure = result.failure) }
                is AppResult.Success -> updateState { VerificationState() }
            }
        }
    }
}
