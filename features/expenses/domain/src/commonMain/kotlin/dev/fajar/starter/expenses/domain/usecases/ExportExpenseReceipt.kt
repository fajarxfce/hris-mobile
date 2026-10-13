@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentFileDeclaration
import dev.fajar.starter.documents.domain.repositories.DocumentOutputRepository
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.repositories.ExpenseReceiptRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Explicit disclosure from an owned claim; Documents catalog access is unrelated. */
class ExportExpenseReceipt(
    private val receipts: ExpenseReceiptRepository,
    private val outputs: DocumentOutputRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        workspaceId: String,
        snapshot: ExpenseSnapshot<ExpenseView>,
        receiptId: String,
    ): AppResult<Boolean> =
        withTimeoutOrNull(8.minutes) {
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val lease = validateExpenseCache(access, snapshot, clock.now())
            if (lease is AppResult.Failed) return@withTimeoutOrNull lease
            val view = snapshot.value
            val submission = view.submission
            if (
                submission == null ||
                    Uuid.parseOrNull(submission.id)?.toString() != submission.id ||
                    submission.id != view.claim.latestSubmissionId ||
                    submission.claimId != view.claim.id ||
                    submission.employeeId != view.claim.employeeId ||
                    submission.claimVersion != view.claim.version ||
                    submission.claimStatus != view.claim.status
            )
                return@withTimeoutOrNull expenseFailure(
                    "expense_receipt_not_found",
                    FailureKind.Unavailable,
                )
            val receipt =
                submission.lines
                    .flatMap { it.receipts }
                    .filter { it.id == receiptId }
                    .distinct()
                    .singleOrNull()
                    ?: return@withTimeoutOrNull expenseFailure(
                        "expense_receipt_not_found",
                        FailureKind.Unavailable,
                    )
            val valid = validateExpenseReceipt(receipt)
            if (valid is AppResult.Failed) return@withTimeoutOrNull valid
            val reviewed =
                withTimeoutOrNull(30.seconds) {
                    review(workspaceId, access, submission.id, receipt)
                }
                    ?: return@withTimeoutOrNull expenseFailure(
                        "client.expense_export_timeout",
                        FailureKind.Timeout,
                    )
            if (reviewed is AppResult.Failed) return@withTimeoutOrNull reviewed
            val requestId = Uuid.random().toString()
            try {
                val selected =
                    withTimeoutOrNull(5.minutes) {
                        outputs.select(
                            requestId,
                            DocumentFileDeclaration(
                                receipt.fileName,
                                receipt.mediaType,
                                receipt.size,
                            ),
                        )
                    }
                        ?: return@withTimeoutOrNull expenseFailure(
                            "client.expense_selection_timeout",
                            FailureKind.Timeout,
                        )
                currentCoroutineContext().ensureActive()
                val continued = validateExpenseContinuation(access, currentAccess(workspaceId))
                if (continued is AppResult.Failed) return@withTimeoutOrNull continued
                when (selected) {
                    is AppResult.Failed -> return@withTimeoutOrNull selected
                    is AppResult.Success ->
                        if (!selected.value) return@withTimeoutOrNull AppResult.Success(false)
                }
                withTimeoutOrNull(2.minutes) {
                    copy(workspaceId, access, submission.id, receipt, requestId)
                } ?: expenseFailure("client.expense_export_timeout", FailureKind.Timeout)
            } finally {
                withContext(NonCancellable) {
                    withTimeoutOrNull(5.seconds) { outputs.release(requestId) }
                }
            }
        } ?: expenseFailure("client.expense_export_timeout", FailureKind.Timeout)

    private suspend fun copy(
        workspaceId: String,
        access: ExpenseAccess,
        submissionId: String,
        receipt: ExpenseReceipt,
        requestId: String,
    ): AppResult<Boolean> {
        var policy =
            when (val result = review(workspaceId, access, submissionId, receipt)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        var offset = 0L
        var failure: AppResult.Failed? = null
        receipts
            .chunks(access, submissionId, receipt)
            .takeWhile { part ->
                val bytes = (part as? AppResult.Success)?.value?.bytes
                try {
                    currentCoroutineContext().ensureActive()
                    if (clock.now() >= companyPolicyDeadline(policy)) {
                        when (val result = review(workspaceId, access, submissionId, receipt)) {
                            is AppResult.Failed -> {
                                failure = result
                                return@takeWhile false
                            }
                            is AppResult.Success -> policy = result.value
                        }
                    }
                    val continued = admitted(workspaceId, access, policy)
                    if (continued is AppResult.Failed) {
                        failure = continued
                        return@takeWhile false
                    }
                    when (part) {
                        is AppResult.Failed -> {
                            failure = part
                            return@takeWhile false
                        }
                        is AppResult.Success -> {
                            val chunk = part.value
                            if (
                                offset >= receipt.size ||
                                    chunk.offset != offset ||
                                    chunk.bytes.size.toLong() !=
                                        minOf(
                                            EXPENSE_RECEIPT_CHUNK_BYTES.toLong(),
                                            receipt.size - offset,
                                        )
                            ) {
                                failure =
                                    expenseFailure(
                                        "client.invalid_response",
                                        FailureKind.Unexpected,
                                    )
                                return@takeWhile false
                            }
                            val written = outputs.write(requestId, offset, chunk.bytes)
                            currentCoroutineContext().ensureActive()
                            val after = admitted(workspaceId, access, policy)
                            if (after is AppResult.Failed) {
                                failure = after
                                return@takeWhile false
                            }
                            if (written is AppResult.Failed) {
                                failure = written
                                return@takeWhile false
                            }
                            offset += chunk.bytes.size
                        }
                    }
                    true
                } finally {
                    bytes?.fill(0)
                }
            }
            .collect()
        failure?.let {
            return it
        }
        if (offset != receipt.size)
            return expenseFailure("client.invalid_response", FailureKind.Unexpected)
        val finalPolicy =
            when (val result = review(workspaceId, access, submissionId, receipt)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val completed = outputs.complete(requestId)
        currentCoroutineContext().ensureActive()
        val continuation = admitted(workspaceId, access, finalPolicy)
        if (continuation is AppResult.Failed) return continuation
        return when (completed) {
            is AppResult.Failed -> completed
            is AppResult.Success -> AppResult.Success(true)
        }
    }

    private suspend fun review(
        workspaceId: String,
        access: ExpenseAccess,
        submissionId: String,
        receipt: ExpenseReceipt,
    ): AppResult<CompanyPolicySnapshot> {
        val before = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (before is AppResult.Failed) return before
        val requestedAt = clock.now()
        val fetched = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        val continued = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (continued is AppResult.Failed) return continued
        val policy =
            when (fetched) {
                is AppResult.Failed -> return fetched
                is AppResult.Success -> CompanyPolicySnapshot(fetched.value, requestedAt)
            }
        val allowed = admitted(workspaceId, access, policy)
        if (allowed is AppResult.Failed) return allowed
        val inspected = receipts.inspect(access, submissionId, receipt)
        currentCoroutineContext().ensureActive()
        val after = admitted(workspaceId, access, policy)
        if (after is AppResult.Failed) return after
        return when (inspected) {
            is AppResult.Failed -> inspected
            is AppResult.Success -> AppResult.Success(policy)
        }
    }

    private suspend fun admitted(
        workspaceId: String,
        access: ExpenseAccess,
        policy: CompanyPolicySnapshot,
    ): AppResult<Unit> {
        val continued = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (continued is AppResult.Failed) return continued
        return admitCompanyOperation(policy, build, CompanyModule.EXPENSES, clock.now())
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<ExpenseAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value
                        ?: return expenseFailure("session_revoked", FailureKind.Unauthorized)
            }
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val latest =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return admitExpenses(workspaceId, workspace, latest, build, clock.now())
    }
}
