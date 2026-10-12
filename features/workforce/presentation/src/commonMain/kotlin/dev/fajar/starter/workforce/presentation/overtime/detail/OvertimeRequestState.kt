package dev.fajar.starter.workforce.presentation.overtime.detail

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.overtimeFailureInvalidatesCache
import dev.fajar.starter.workforce.presentation.overtime.action.OvertimeActionKind
import dev.fajar.starter.workforce.presentation.overtime.verificationCodes

data class OvertimeRequestState(
    val selection: OvertimeRequestSelection,
    val content: OvertimeSnapshot<OvertimeRequestView>? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val cacheFailure: Failure? = null,
    val refreshFailure: Failure? = null,
) {
    val readRestricted: Boolean
        get() =
            cacheFailure?.let(::overtimeFailureInvalidatesCache) == true ||
                refreshFailure?.let(::overtimeFailureInvalidatesCache) == true

    val failure: Failure?
        get() =
            cacheFailure?.takeIf(::overtimeFailureInvalidatesCache)
                ?: refreshFailure
                ?: cacheFailure

    val needsVerification: Boolean
        get() = failure?.code in verificationCodes

    val actionOptions: Set<OvertimeActionKind>
        get() =
            if (
                readRestricted ||
                    content?.access?.permissions?.any {
                        it == "overtime.self.manage" || it == "overtime.manage"
                    } != true
            )
                emptySet()
            else
                OvertimeActionKind.entries
                    .filter { it.action in content.value.details.actions }
                    .toSet()

    val canOpenAction: Boolean
        get() = !loading && !refreshing && !readRestricted
}
