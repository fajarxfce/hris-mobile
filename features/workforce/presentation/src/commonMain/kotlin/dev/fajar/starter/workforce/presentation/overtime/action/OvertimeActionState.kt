package dev.fajar.starter.workforce.presentation.overtime.action

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.presentation.overtime.verificationCodes

data class OvertimeActionState(
    val target: OvertimeActionTarget,
    val content: OvertimeSnapshot<OvertimeRequestView>? = null,
    val form: OvertimeActionForm? = null,
    val command: OvertimeCommandRecord? = null,
    val loading: Boolean = true,
    val commandReady: Boolean = false,
    val work: OvertimeActionWork? = null,
    val failure: Failure? = null,
    val restriction: Failure? = null,
    val dialog: OvertimeActionDialog? = null,
) {
    val visibleFailure: Failure?
        get() = restriction ?: failure

    val needsVerification: Boolean
        get() = visibleFailure?.code in verificationCodes

    val requestChanged: Boolean
        get() =
            form != null &&
                content != null &&
                form.requestVersion != content.value.details.request.version

    val available: Boolean
        get() =
            restriction == null &&
                target.kind.action in content?.value?.details?.actions.orEmpty() &&
                content?.access?.permissions?.any {
                    it == "overtime.self.manage" || it == "overtime.manage"
                } == true

    val editable: Boolean
        get() =
            available &&
                !requestChanged &&
                commandReady &&
                command == null &&
                work == null &&
                form != null

    val canSubmit: Boolean
        get() = editable && dialog == null

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

    val kind: OvertimeActionKind
        get() =
            when (command?.command?.payload) {
                is OvertimeCommandPayload.Actual -> OvertimeActionKind.ACTUAL
                is OvertimeCommandPayload.Withdrawal -> OvertimeActionKind.WITHDRAW
                else -> target.kind
            }
}

enum class OvertimeActionWork {
    REFRESH,
    SENDING,
    OPENING,
    DISCARDING,
}
