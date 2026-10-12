package dev.fajar.starter.workforce.presentation.overtime.plan

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.overtimeFailure
import dev.fajar.starter.workforce.presentation.overtime.verificationCodes
import kotlinx.datetime.LocalDate

data class OvertimePlanState(
    val form: OvertimePlanForm? = null,
    val prepared: OvertimePreparation? = null,
    val reviewExpired: Boolean = false,
    val command: OvertimeCommandRecord? = null,
    val commandReady: Boolean = false,
    val work: OvertimePlanWork? = null,
    val failure: Failure? = null,
    val restriction: Failure? = null,
    val dialog: OvertimePlanDialog? = null,
) {
    val editable: Boolean
        get() = restriction == null && commandReady && command == null && work == null

    val canSubmit: Boolean
        get() = editable && prepared != null && !reviewExpired && dialog == null

    val visibleFailure: Failure?
        get() = restriction ?: failure

    val needsVerification: Boolean
        get() = visibleFailure?.code in verificationCodes

    val canRetry: Boolean
        get() =
            restriction == null &&
                commandReady &&
                work == null &&
                (command?.outcome is OvertimeCommandOutcome.Pending ||
                    command?.outcome is OvertimeCommandOutcome.Unconfirmed)

    val canDiscard: Boolean
        get() =
            restriction == null &&
                commandReady &&
                work == null &&
                (command?.outcome is OvertimeCommandOutcome.Pending ||
                    command?.outcome is OvertimeCommandOutcome.Rejected)
}

enum class OvertimePlanWork {
    REVIEW,
    SENDING,
    OPENING,
    DISCARDING,
}

internal fun initialOvertimePlanState(target: OvertimePlanTarget): OvertimePlanState {
    val date = LocalDate.Formats.ISO.parseOrNull(target.workDate)?.takeIf { it.year in 2000..2100 }
    return if (date == null)
        OvertimePlanState(failure = overtimeFailure("overtime_date_out_of_range").failure)
    else OvertimePlanState(form = OvertimePlanForm(date))
}
