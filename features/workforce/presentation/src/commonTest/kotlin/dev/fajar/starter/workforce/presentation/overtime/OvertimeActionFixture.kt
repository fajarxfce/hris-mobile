package dev.fajar.starter.workforce.presentation.overtime

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.usecases.*
import dev.fajar.starter.workforce.presentation.overtime.action.*
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Shares the CAS command-store fixture with planning; the remote owns independent request facts.
 */
class OvertimeActionFixture(
    dispatcher: CoroutineDispatcher,
    val kind: OvertimeActionKind = OvertimeActionKind.ACTUAL,
) {
    val persistence = OvertimePlanFixture(dispatcher)
    val base = persistence.base
    val target = OvertimeActionTarget(base.workspace.id, base.selection, kind)
    var latest = base.details
    private val receipts = mutableMapOf<String, Pair<OvertimeCommand, OvertimeCommandReceipt>>()

    init {
        base.detailsCall = { AppResult.Success(latest) }
        persistence.transmit = { accept(it) }
    }

    fun accept(command: OvertimeCommand): AppResult<OvertimeCommandReceipt> {
        receipts[command.id]?.let {
            assertEquals(it.first, command)
            return AppResult.Success(it.second)
        }
        val request = latest.request
        val payload = command.payload
        val actual = (payload as? OvertimeCommandPayload.Actual)?.actual ?: request.actual
        val expected =
            when (payload) {
                is OvertimeCommandPayload.Actual -> payload.expectedVersion
                is OvertimeCommandPayload.Withdrawal -> payload.expectedVersion
                is OvertimeCommandPayload.Plan ->
                    error("Request actions cannot create another plan")
            }
        assertEquals(request.version, expected)
        val withdrawal = payload is OvertimeCommandPayload.Withdrawal
        val status = if (withdrawal) OvertimeStatus.WITHDRAWN else OvertimeStatus.PENDING
        val approvalId =
            if (withdrawal) request.approvalId else "88888888-8888-4888-8888-888888888888"
        val approval =
            if (withdrawal)
                latest.approval?.copy(
                    status = OvertimeApprovalStatus.CANCELLED,
                    version = latest.approval!!.version + 1,
                )
            else
                OvertimeApproval(
                    requireNotNull(approvalId),
                    OvertimeApprovalStatus.PENDING,
                    0,
                    1,
                    0,
                )
        val changed =
            request.copy(
                actual = actual,
                status = status,
                submittedBy = if (withdrawal) request.submittedBy else base.session.user.id,
                submittedAt = if (withdrawal) request.submittedAt else base.clock.now(),
                approvalId = approvalId,
                decidedAt = if (withdrawal) base.clock.now() else null,
                version = expected + 1,
            )
        latest =
            latest.copy(
                request = changed,
                approval = approval,
                history =
                    OvertimePage(
                        listOf(
                            OvertimeChange(
                                expected + 1,
                                if (withdrawal) OvertimeChangeKind.WITHDRAWN
                                else OvertimeChangeKind.ACTUAL_SUBMITTED,
                                status,
                                actual,
                                0,
                                approvalId,
                                base.session.user.id,
                                base.clock.now(),
                                command.reason,
                            )
                        ) + latest.history.items
                    ),
                actions = if (withdrawal) emptySet() else setOf(OvertimeAction.WITHDRAW),
            )
        val receipt = OvertimeCommandReceipt(command.requestId, expected + 1)
        receipts[command.id] = command to receipt
        return AppResult.Success(receipt)
    }

    private val prepare =
        PrepareOvertimeAction(
            persistence.commands,
            base.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
            dispatcher,
        )
    private val observeCommand =
        ObserveOvertimeCommand(
            persistence.commands,
            base.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    private val replay =
        ReplayOvertimeCommands(
            persistence.commands,
            persistence.remote,
            base.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    private val review =
        ReviewOvertimeCommand(
            persistence.commands,
            base.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
            OvertimeWorkingPresentationFixture(base).copies,
        )
    private val observeRequest =
        ObserveOvertimeRequest(base.cache, base.sessions, base.workspaces, base.clock, base.build)

    fun create(store: ViewModelStore, requestedKind: OvertimeActionKind = kind) =
        OvertimeActionViewModel(
                target.copy(kind = requestedKind),
                observeRequest,
                base.refreshRequest,
                observeCommand,
                prepare,
                replay,
                review,
                base.sync,
            )
            .also { store.put("action", it) }
}
