package dev.fajar.starter.workforce.presentation.overtime.overview

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.overtimeFailureInvalidatesCache
import dev.fajar.starter.workforce.presentation.overtime.verificationCodes

data class OvertimeState(
    val selection: OvertimeSelection? = null,
    val content: OvertimeSnapshot<OvertimeOverview>? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val cacheFailure: Failure? = null,
    val refreshFailure: Failure? = null,
    val dialog: OvertimeDialog? = null,
    val pickerYear: Int? = null,
) {
    val filters: OvertimeSelection?
        get() = selection ?: content?.value?.selection

    val month: OvertimeMonthFilter?
        get() = filters?.period?.from?.let(::overtimeMonthFilter)

    val picker: OvertimeMonthPicker?
        get() = pickerYear?.let(::overtimeMonthPicker)

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
}

enum class OvertimeDialog {
    MONTH,
    STATUS,
    EMPLOYMENT,
}
