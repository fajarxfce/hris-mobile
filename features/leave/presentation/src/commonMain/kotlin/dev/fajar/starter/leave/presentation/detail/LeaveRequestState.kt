package dev.fajar.starter.leave.presentation.detail

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.leave.domain.entities.LeaveRequestSelection
import dev.fajar.starter.leave.domain.entities.LeaveRequestSnapshot
import dev.fajar.starter.leave.domain.policies.leaveFailureInvalidatesCache

data class LeaveRequestState(
    val selection: LeaveRequestSelection? = null,
    val content: LeaveRequestSnapshot? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val cacheFailure: Failure? = null,
    val refreshFailure: Failure? = null,
) {
    val visibleSelection: LeaveRequestSelection?
        get() = selection ?: content?.selection

    val readRestricted: Boolean
        get() =
            cacheFailure?.let(::leaveFailureInvalidatesCache) == true ||
                refreshFailure?.let(::leaveFailureInvalidatesCache) == true

    val failure: Failure?
        get() =
            cacheFailure?.takeIf(::leaveFailureInvalidatesCache) ?: refreshFailure ?: cacheFailure

    val needsVerification: Boolean
        get() =
            cacheFailure?.code in requestVerificationCodes ||
                refreshFailure?.code in requestVerificationCodes
}

internal val requestVerificationCodes =
    setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
