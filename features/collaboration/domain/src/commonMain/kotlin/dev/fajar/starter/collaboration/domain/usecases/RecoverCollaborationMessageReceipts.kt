package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Repairs at most 32 missing ACK receipts from up to 128 owned commands and exact local originals.
 */
class RecoverCollaborationMessageReceipts(
    private val history: CollaborationHistoryRepository,
    private val messages: CollaborationMessageRepository,
    private val receipts: CollaborationReceiptRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val completions: CollaborationMessageCompletionRepository,
) {
    private val execution = Mutex()

    suspend operator fun invoke(workspaceId: String, conversationId: String): AppResult<Int> =
        invoke(CollaborationLocalScope.Selected(workspaceId), conversationId)

    suspend operator fun invoke(
        scope: CollaborationLocalScope,
        conversationId: String,
    ): AppResult<Int> =
        execution.withLock {
            if (!validCollaborationMessageId(conversationId)) return@withLock failed()
            val captured = capture(scope)
            if (captured is AppResult.Failed) return@withLock captured
            val access = (captured as AppResult.Success).value
            continuation(scope, access).let { if (it is AppResult.Failed) return@withLock it }
            val journal =
                checked(scope, access) {
                    when (val read = receipts.read(access, conversationId)) {
                        is AppResult.Failed -> read
                        is AppResult.Success ->
                            AppResult.Success(
                                read.value.map { it.copy(eventIds = it.eventIds.toList()) }
                            )
                    }
                }
            if (journal is AppResult.Failed) return@withLock journal
            val known = (journal as AppResult.Success).value
            val read =
                checked(scope, access) {
                    when (val current = messages.observe(access, conversationId).first()) {
                        is AppResult.Failed -> current
                        is AppResult.Success -> AppResult.Success(current.value.toList())
                    }
                }
            if (read is AppResult.Failed) return@withLock read
            val records = (read as AppResult.Success).value
            val retained = completedReceipts(scope, access, conversationId, records, known)
            if (retained is AppResult.Failed) return@withLock retained
            if (
                collaborationReceiptProjection(
                    access,
                    conversationId,
                    records,
                    known,
                    (retained as AppResult.Success).value,
                ) == null || records.any { !validCollaborationMessageState(it.state) }
            )
                return@withLock failed()
            val found =
                recover(scope, access, conversationId, records, known.map { it.intent.id }.toSet())
            if (found is AppResult.Failed) return@withLock found
            val batch = (found as AppResult.Success).value
            if (batch.isEmpty()) return@withLock AppResult.Success(0)
            if (!validCollaborationReceiptLedger(known + batch))
                return@withLock collaborationSetupFailure(
                    "client.collaboration_receipt_capacity",
                    FailureKind.Storage,
                )
            val appended = checked(scope, access) { receipts.append(access, conversationId, batch) }
            if (appended is AppResult.Failed) return@withLock appended
            if (!(appended as AppResult.Success).value) return@withLock failed()
            AppResult.Success(batch.size)
        }

    private suspend fun recover(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
        conversationId: String,
        records: List<CollaborationMessageRecord>,
        known: Set<String>,
    ): AppResult<List<CollaborationMessageReceipt>> {
        val batch = mutableListOf<CollaborationMessageReceipt>()
        for (record in records) {
            if (record.intent.id in known) continue
            val state = record.state as? CollaborationMessageState.Staged ?: continue
            val origin = state.origin ?: continue
            val read =
                checked(scope, access) {
                    when (
                        val result =
                            history.getByLogicalId(
                                access,
                                conversationId,
                                state.device.roomId,
                                origin.sender,
                                record.intent.id,
                            )
                    ) {
                        is AppResult.Failed -> result
                        is AppResult.Success ->
                            AppResult.Success(
                                result.value?.let { it.copy(eventIds = it.eventIds.toList()) }
                            )
                    }
                }
            if (read is AppResult.Failed) return read
            val original = (read as AppResult.Success).value ?: continue
            val receipt = recoverCollaborationOriginalReceipt(record, original) ?: return failed()
            batch += receipt
            if (batch.size == 32) break
        }
        return AppResult.Success(batch.toList())
    }

    private suspend fun completedReceipts(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
        conversationId: String,
        records: List<CollaborationMessageRecord>,
        journal: List<CollaborationMessageReceipt>,
    ): AppResult<List<CollaborationMessageCompletion>> {
        val ids =
            missingCollaborationReceiptIds(access, conversationId, records, journal)
                ?: return failed()
        val result = mutableListOf<CollaborationMessageCompletion>()
        for (id in ids) {
            val read =
                checked(scope, access) {
                    when (val proof = completions.readCompletion(access, id)) {
                        is AppResult.Failed -> proof
                        is AppResult.Success ->
                            AppResult.Success(
                                proof.value?.let { it.copy(eventIds = it.eventIds.toList()) }
                            )
                    }
                }
            if (read is AppResult.Failed) return read
            result += (read as AppResult.Success).value ?: return failed()
        }
        return AppResult.Success(result.toList())
    }

    private suspend fun <T> checked(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
        operation: suspend () -> AppResult<T>,
    ): AppResult<T> {
        val result = operation()
        val current = continuation(scope, access)
        return if (current is AppResult.Failed) current else result
    }

    private suspend fun capture(scope: CollaborationLocalScope) =
        sessions.captureMessageAccess(scope, workspaces, server, build, clock)

    private suspend fun continuation(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = lists.admission(access)
        // Capture after gate I/O so an account replacement during that read cannot publish success.
        val current = capture(scope)
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != access)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }

    private fun failed() = collaborationSetupFailure("client.collaboration_receipt_unresolved")
}
