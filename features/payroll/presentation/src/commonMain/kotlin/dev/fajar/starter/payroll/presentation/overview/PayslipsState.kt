package dev.fajar.starter.payroll.presentation.overview

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.payslipFailureInvalidatesCache
import dev.fajar.starter.payroll.presentation.payslipVerificationCodes

data class PayslipsState(
    val selection: PayslipSelection? = null,
    val content: PayslipSnapshot<PayslipOverview>? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val syncPending: Boolean = false,
    val cacheFailure: Failure? = null,
    val refreshFailure: Failure? = null,
) {
    val filters: PayslipSelection?
        get() = selection ?: content?.value?.selection

    val readRestricted: Boolean
        get() =
            cacheFailure?.let(::payslipFailureInvalidatesCache) == true ||
                refreshFailure?.let(::payslipFailureInvalidatesCache) == true

    val failure: Failure?
        get() =
            cacheFailure?.takeIf(::payslipFailureInvalidatesCache) ?: refreshFailure ?: cacheFailure

    val needsVerification: Boolean
        get() = failure?.code in payslipVerificationCodes
}
