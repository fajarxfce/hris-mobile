package dev.fajar.starter.expenses.presentation

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.presentation.action.*
import kotlin.time.Instant

class ExpenseActionFixture(instant: () -> Instant) {
    val editor = ExpenseEditorFixture(instant)
    val f = editor.f
    var submission: ExpenseSubmission? = null

    init {
        f.submissionCall = { AppResult.Success(checkNotNull(submission)) }
        editor.transport = { command ->
            publish(command)
            AppResult.Success(
                ExpenseCommandReceipt(command.claimId, command.expectedReceiptVersion())
            )
        }
    }

    fun publish(command: ExpenseCommand) {
        val claim = editor.currentClaim
        val version = command.expectedReceiptVersion()
        when (val payload = command.payload) {
            is ExpenseCommandPayload.Submit -> {
                editor.currentClaim =
                    claim.copy(
                        version = version,
                        status = ExpenseStatus.PENDING,
                        submissionCount = claim.submissionCount + 1,
                        latestSubmissionId = payload.submissionId,
                    )
                submission =
                    ExpenseSubmission(
                        payload.submissionId,
                        claim.id,
                        claim.employeeId,
                        claim.submissionCount + 1,
                        claim.draft.revision,
                        claim.draft.title,
                        claim.draft.description,
                        claim.draft.total,
                        "IDR",
                        claim.draft.lines.map { line ->
                            ExpenseSubmittedLine(
                                line.id,
                                line.occurredOn,
                                line.amount,
                                line.description,
                                ExpenseCategoryReference(line.categoryId, "TRAVEL", "Travel", 0, 0),
                                null,
                                emptyList(),
                            )
                        },
                        f.clock.now(),
                        command.reason,
                        ExpenseApproval(
                            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                            ExpenseApprovalStatus.PENDING,
                            0,
                            0,
                            listOf(setOf(f.session.user.id)),
                        ),
                        version,
                        ExpenseStatus.PENDING,
                        true,
                        emptyList(),
                    )
            }
            is ExpenseCommandPayload.Cancel -> {
                editor.currentClaim =
                    claim.copy(version = version, status = ExpenseStatus.CANCELLED)
                submission =
                    submission?.copy(claimVersion = version, claimStatus = ExpenseStatus.CANCELLED)
            }
            is ExpenseCommandPayload.Withdraw -> {
                editor.currentClaim = claim.copy(version = version, status = ExpenseStatus.DRAFT)
                submission =
                    submission?.let {
                        it.copy(
                            claimVersion = version,
                            claimStatus = ExpenseStatus.DRAFT,
                            approval = it.approval.copy(status = ExpenseApprovalStatus.CANCELLED),
                        )
                    }
            }
            is ExpenseCommandPayload.SaveDraft -> editor.publish(command)
        }
    }

    fun model(mode: ExpenseActionMode = ExpenseActionMode.SUBMIT) =
        ExpenseActionViewModel(
            ExpenseActionTarget(f.workspace.id, f.claim.id, mode),
            f.observeDetail,
            f.refreshDetail,
            editor.observe,
            editor.action,
            editor.replay,
            editor.review,
        )
}
