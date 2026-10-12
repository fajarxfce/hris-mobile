package dev.fajar.starter.leave.presentation.detail

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*

data class LeaveRequestState(
    val selection: LeaveRequestSelection? = null,
    val content: LeaveRequestSnapshot? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val cacheFailure: Failure? = null,
    val refreshFailure: Failure? = null,
    val action: LeaveActionState = LeaveActionState(),
) {
    val visibleSelection: LeaveRequestSelection?
        get() = selection ?: content?.selection

    val readRestricted: Boolean
        get() =
            cacheFailure?.let(::leaveFailureInvalidatesCache) == true ||
                refreshFailure?.let(::leaveFailureInvalidatesCache) == true ||
                action.failure?.let(::leaveFailureInvalidatesCache) == true

    val failure: Failure?
        get() =
            cacheFailure?.takeIf(::leaveFailureInvalidatesCache)
                ?: refreshFailure?.takeIf(::leaveFailureInvalidatesCache)
                ?: action.failure?.takeIf(::leaveFailureInvalidatesCache)
                ?: refreshFailure
                ?: action.failure
                ?: cacheFailure

    val needsVerification: Boolean
        get() =
            cacheFailure?.code in requestVerificationCodes ||
                refreshFailure?.code in requestVerificationCodes ||
                action.failure?.code in requestVerificationCodes

    val actionOptions: List<LeaveRequestAction>
        get() {
            val snapshot = content ?: return emptyList()
            if (readRestricted || admitLeaveWrite(snapshot.access) is AppResult.Failed)
                return emptyList()
            return LeaveRequestAction.entries.filter(snapshot.request::allows)
        }

    val availableActions: List<LeaveRequestAction>
        get() =
            if (refreshing || action.working || !action.ready || action.command != null) emptyList()
            else actionOptions

    val canRetryAction: Boolean
        get() =
            action.ready &&
                !readRestricted &&
                !refreshing &&
                !action.working &&
                action.command?.let {
                    it.attempts < LEAVE_MAXIMUM_ATTEMPTS &&
                        (it.outcome == LeaveCommandOutcome.Pending ||
                            it.outcome is LeaveCommandOutcome.Unconfirmed)
                } == true

    val canReviewAction: Boolean
        get() =
            action.ready &&
                !readRestricted &&
                !refreshing &&
                !action.working &&
                (action.command?.outcome is LeaveCommandOutcome.Confirmed ||
                    action.command?.outcome is LeaveCommandOutcome.Rejected)

    val canDiscardAction: Boolean
        get() =
            action.ready &&
                !readRestricted &&
                !refreshing &&
                !action.working &&
                action.command?.outcome == LeaveCommandOutcome.Pending
}

internal val requestVerificationCodes =
    setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
