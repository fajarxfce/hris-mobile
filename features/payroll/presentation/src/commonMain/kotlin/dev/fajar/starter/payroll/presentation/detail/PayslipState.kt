package dev.fajar.starter.payroll.presentation.detail

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.payslipFailureInvalidatesCache
import dev.fajar.starter.payroll.presentation.payslipVerificationCodes

data class PayslipState(
    val content: PayslipSnapshot<PayslipView>? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val exportOptions: Boolean = false,
    val cacheFailure: Failure? = null,
    val refreshFailure: Failure? = null,
    val exportFailure: Failure? = null,
) {
    val restriction: Failure?
        get() =
            cacheFailure?.takeIf {
                payslipFailureInvalidatesCache(it) && it.code != "client.payslip_cache_expired"
            }
                ?: refreshFailure?.takeIf(::payslipFailureInvalidatesCache)
                ?: exportFailure?.takeIf(::payslipFailureInvalidatesCache)
                ?: cacheFailure?.takeIf(::payslipFailureInvalidatesCache)

    val readRestricted: Boolean
        get() = restriction != null

    val failure: Failure?
        get() =
            cacheFailure?.takeIf {
                payslipFailureInvalidatesCache(it) && it.code != "client.payslip_cache_expired"
            }
                ?: refreshFailure?.takeIf(::payslipFailureInvalidatesCache)
                ?: exportFailure?.takeIf(::payslipFailureInvalidatesCache)
                ?: refreshFailure
                ?: exportFailure
                ?: cacheFailure?.takeUnless {
                    refreshing && it.code == "client.payslip_cache_expired"
                }

    val needsVerification: Boolean
        get() = failure?.code in payslipVerificationCodes

    val canSave: Boolean
        get() = content != null && !readRestricted && !loading && !refreshing && !saving
}
