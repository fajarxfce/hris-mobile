package dev.fajar.starter.expenses.presentation

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentFileDeclaration
import dev.fajar.starter.documents.domain.repositories.DocumentOutputRepository
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.EXPENSE_RECEIPT_CHUNK_BYTES
import dev.fajar.starter.expenses.domain.repositories.ExpenseReceiptRepository
import dev.fajar.starter.expenses.domain.usecases.ExportExpenseReceipt
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.*

class ExpenseReceiptExportFixture(val base: ExpenseFixture = ExpenseFixture()) {
    val receipt =
        ExpenseReceipt(
            "99999999-9999-4999-8999-999999999999",
            "receipt.pdf",
            "application/pdf",
            EXPENSE_RECEIPT_CHUNK_BYTES + 13L,
            "a".repeat(64),
            false,
        )
    val submission =
        ExpenseSubmission(
            "88888888-8888-4888-8888-888888888888",
            base.claim.id,
            base.employee.id,
            1,
            0,
            base.claim.draft.title,
            base.claim.draft.description,
            base.amount,
            "IDR",
            listOf(
                ExpenseSubmittedLine(
                    base.line.id,
                    base.line.occurredOn,
                    base.line.amount,
                    base.line.description,
                    ExpenseCategoryReference(base.line.categoryId, "TRAVEL", "Travel", 0, 0),
                    null,
                    listOf(receipt),
                )
            ),
            base.clock.now(),
            "Travel reimbursement",
            ExpenseApproval(
                "77777777-7777-4777-8777-777777777777",
                ExpenseApprovalStatus.PENDING,
                0,
                0,
                listOf(setOf(base.session.user.id)),
            ),
            1,
            ExpenseStatus.PENDING,
            true,
            emptyList(),
        )
    val claim =
        base.claim.copy(
            version = 1,
            status = ExpenseStatus.PENDING,
            submissionCount = 1,
            latestSubmissionId = submission.id,
        )
    var snapshot =
        ExpenseSnapshot(
            base.access,
            "generation",
            ExpenseView(claim, submission, base.history, null, emptyList()),
            base.clock.now(),
            base.clock.now() + 1.hours,
        )
    var inspectCall: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
    var selectCall: suspend () -> AppResult<Boolean> = { AppResult.Success(true) }
    var writeCall: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
    var completeCall: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
    var releaseCall: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
    var parts: () -> Flow<AppResult<ExpenseReceiptChunk>> = {
        flow {
            emit(
                AppResult.Success(
                    ExpenseReceiptChunk(
                        0,
                        ByteArray(EXPENSE_RECEIPT_CHUNK_BYTES) { 42 }.also { acquired += it },
                    )
                )
            )
            emit(
                AppResult.Success(
                    ExpenseReceiptChunk(
                        EXPENSE_RECEIPT_CHUNK_BYTES.toLong(),
                        ByteArray(13) { 43 }.also { acquired += it },
                    )
                )
            )
        }
    }
    val acquired = mutableListOf<ByteArray>()
    val written = mutableListOf<Pair<Long, ByteArray>>()
    var inspections = 0
    var streams = 0
    var selections = 0
    var completions = 0
    var releases = 0
    var declaration: DocumentFileDeclaration? = null
    var requestId: String? = null
    val repository =
        object : ExpenseReceiptRepository {
            override suspend fun inspect(
                access: ExpenseAccess,
                submissionId: String,
                receipt: ExpenseReceipt,
            ): AppResult<Unit> {
                check(
                    access.companyId == base.access.companyId &&
                        submissionId == submission.id &&
                        receipt.id == this@ExpenseReceiptExportFixture.receipt.id
                )
                inspections++
                return inspectCall()
            }

            override fun chunks(
                access: ExpenseAccess,
                submissionId: String,
                receipt: ExpenseReceipt,
            ) = flow {
                streams++
                emitAll(parts())
            }
        }
    val output =
        object : DocumentOutputRepository {
            override suspend fun select(
                requestId: String,
                declaration: DocumentFileDeclaration,
            ): AppResult<Boolean> {
                selections++
                this@ExpenseReceiptExportFixture.requestId = requestId
                this@ExpenseReceiptExportFixture.declaration = declaration
                return selectCall()
            }

            override suspend fun write(
                requestId: String,
                offset: Long,
                bytes: ByteArray,
            ): AppResult<Unit> {
                check(requestId == this@ExpenseReceiptExportFixture.requestId)
                written += offset to bytes.copyOf()
                return writeCall()
            }

            override suspend fun complete(requestId: String): AppResult<Unit> {
                check(requestId == this@ExpenseReceiptExportFixture.requestId)
                completions++
                return completeCall()
            }

            override suspend fun release(requestId: String): AppResult<Unit> {
                check(requestId == this@ExpenseReceiptExportFixture.requestId)
                releases++
                return releaseCall()
            }
        }
    val export =
        ExportExpenseReceipt(
            repository,
            output,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )

    fun publish() {
        base.claimCall = { AppResult.Success(claim) }
        base.submissionCall = { AppResult.Success(submission) }
        base.historyCall = {
            AppResult.Success(
                ExpensePage(
                    listOf(
                        base.change.copy(
                            version = 1,
                            status = ExpenseStatus.PENDING,
                            kind = ExpenseChangeKind.SUBMITTED,
                        )
                    )
                )
            )
        }
    }
}
