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

/** Repairs interrupted receipt-to-command projection using owned local facts, including offline. */
class ReconcileCollaborationMessages(
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
        execution.withLock {
            if (!validCollaborationMessageId(conversationId)) return@withLock failed()
            val captured = capture(workspaceId)
            if (captured is AppResult.Failed) return@withLock captured
            val access = (captured as AppResult.Success).value
            continuation(workspaceId, access).let { if (it is AppResult.Failed) return@withLock it }
            val journal = checked(workspaceId, access) { receipts.read(access, conversationId) }
            if (journal is AppResult.Failed) return@withLock journal
            val acknowledged = (journal as AppResult.Success).value
            if (acknowledged.isEmpty()) return@withLock AppResult.Success(0)
            val read =
                checked(workspaceId, access) { messages.observe(access, conversationId).first() }
            if (read is AppResult.Failed) return@withLock read
            val records = (read as AppResult.Success).value
            val retained =
                completedReceipts(workspaceId, access, conversationId, records, acknowledged)
            if (retained is AppResult.Failed) return@withLock retained
            val updates =
                collaborationReceiptProjection(
                    access,
                    conversationId,
                    records,
                    acknowledged,
                    (retained as AppResult.Success).value,
                ) ?: return@withLock failed()
            var changed = 0
            for ((record, sent) in updates) {
                if (record.state == sent) continue
                val saved = checked(workspaceId, access) { messages.update(access, record, sent) }
                if (saved is AppResult.Failed) return@withLock saved
                if (!(saved as AppResult.Success).value) return@withLock failed()
                changed++
            }
            AppResult.Success(changed)
        }

    private suspend fun completedReceipts(
        workspaceId: String,
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
                checked(workspaceId, access) {
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
        workspaceId: String,
        access: CollaborationLocalAccess,
        operation: suspend () -> AppResult<T>,
    ): AppResult<T> {
        val result = operation()
        val current = continuation(workspaceId, access)
        return if (current is AppResult.Failed) current else result
    }

    private suspend fun capture(workspaceId: String) =
        sessions.captureMessageAccess(workspaceId, workspaces, server, build, clock)

    private suspend fun continuation(
        workspaceId: String,
        access: CollaborationLocalAccess,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = lists.admission(access)
        // Capture after gate I/O so an account replacement during that read cannot publish success.
        val current = capture(workspaceId)
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
