package dev.fajar.starter.payroll.presentation.detail

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.payslipFailureInvalidatesCache
import dev.fajar.starter.payroll.presentation.payslipVerificationCodes

data class PayslipState(
    val content: PayslipSnapshot<PayslipView>? = null,
    val selectedRevision: Long? = null,
    val currentRevision: Long? = null,
    val revisionLoading: Boolean = false,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val syncPending: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val exportRevision: Long? = null,
    val cacheFailure: Failure? = null,
    val refreshFailure: Failure? = null,
    val exportFailure: Failure? = null,
    val revisionFailure: Failure? = null,
) {
    val restriction: Failure?
        get() =
            cacheFailure?.takeIf(::payslipFailureInvalidatesCache)
                ?: refreshFailure?.takeIf(::payslipFailureInvalidatesCache)
                ?: exportFailure?.takeIf(::payslipFailureInvalidatesCache)
                ?: revisionFailure?.takeIf(::payslipFailureInvalidatesCache)

    val readRestricted: Boolean
        get() = restriction != null

    val failure: Failure?
        get() = restriction ?: revisionFailure ?: refreshFailure ?: exportFailure ?: cacheFailure

    val needsVerification: Boolean
        get() = failure?.code in payslipVerificationCodes

    val canSave: Boolean
        get() =
            content != null &&
                !readRestricted &&
                !loading &&
                !refreshing &&
                !revisionLoading &&
                !saving

    val displayedRevision: Long?
        get() = selectedRevision ?: currentRevision

    val canChooseRevision: Boolean
        get() =
            currentRevision != null &&
                !readRestricted &&
                !refreshing &&
                !saving &&
                exportRevision == null

    val canChoosePrevious: Boolean
        get() = canChooseRevision && (displayedRevision ?: 0) > 0

    val canChooseNext: Boolean
        get() = canChooseRevision && (displayedRevision ?: 0) < (currentRevision ?: 0)
}
