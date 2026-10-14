package dev.fajar.starter.leave.presentation.overview

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.leave.domain.entities.LeaveOverview
import dev.fajar.starter.leave.domain.entities.LeaveSelection
import dev.fajar.starter.leave.domain.policies.leaveFailureInvalidatesCache

data class LeaveState(
    val selection: LeaveSelection? = null,
    val content: LeaveOverview? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val syncPending: Boolean = false,
    val cacheFailure: Failure? = null,
    val refreshFailure: Failure? = null,
    val dialog: LeaveDialog? = null,
) {
    val showCreateAction: Boolean
        get() =
            !readRestricted &&
                content?.selection?.employeeId != null &&
                content.access.permissions.contains("leave.self.manage")

    val canCreate: Boolean
        get() = showCreateAction

    val filters: LeaveSelection?
        get() = selection ?: content?.selection

    val readRestricted: Boolean
        get() =
            cacheFailure?.let(::leaveFailureInvalidatesCache) == true ||
                refreshFailure?.let(::leaveFailureInvalidatesCache) == true

    val needsVerification: Boolean
        get() = cacheFailure?.code in verificationCodes || refreshFailure?.code in verificationCodes

    val failure: Failure?
        get() = refreshFailure ?: cacheFailure
}

enum class LeaveDialog {
    STATUS,
    EMPLOYMENT,
}

internal val verificationCodes =
    setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
