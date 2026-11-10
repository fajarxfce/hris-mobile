package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Projects exact retained acknowledgements offline. Does not send, archive or retire receipts. */
class ReconcileCollaborationSpaceMessages(
    private val messages: CollaborationSpaceMessageRepository,
    private val receipts: CollaborationSpaceReceiptRepository,
    private val completions: CollaborationSpaceMessageCompletionRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
    ): AppResult<Int> =
        withTimeoutOrNull(15_000) { execution.withLock { reconcile(access, conversationId) } }
            ?: unresolved(FailureKind.Unavailable)

    private suspend fun reconcile(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
    ): AppResult<Int> {
        if (!validCollaborationSpaceReceiptLedger(access, conversationId, emptyList()))
            return unresolved()
        admit(access).let { if (it is AppResult.Failed) return it }
        val read = receipts.read(access, conversationId)
        // Validate and detach callback-owned collections before admission can suspend.
        val journal =
            when (read) {
                is AppResult.Failed -> read
                is AppResult.Success ->
                    if (validCollaborationSpaceReceiptLedger(access, conversationId, read.value))
                        AppResult.Success(
                            read.value.map { it.copy(eventIds = it.eventIds.toList()) }
                        )
                    else unresolved()
            }
        admit(access).let { if (it is AppResult.Failed) return it }
        if (journal is AppResult.Failed) return journal
        val captured = (journal as AppResult.Success).value
        if (captured.isEmpty()) return AppResult.Success(0)
        val readProofs = readOriginals(access, captured)
        if (readProofs is AppResult.Failed) return readProofs
        val (records, archived) = (readProofs as AppResult.Success).value
        val plan =
            collaborationSpaceReceiptProjection(access, conversationId, records, captured, archived)
                ?: return unresolved()
        var confirmed = 0
        for ((record, sent) in plan) {
            if (record.state == sent) continue
            admit(access).let { if (it is AppResult.Failed) return it }
            val written = messages.update(access, record, sent)
            admit(access).let { if (it is AppResult.Failed) return it }
            // False or a lost response can follow another writer's exact successful transition.
            val readback = messages.get(access, record.intent.id)
            admit(access).let { if (it is AppResult.Failed) return it }
            if (readback is AppResult.Failed) return readback
            val expected = record.copy(revision = record.revision + 1, state = sent)
            if ((readback as AppResult.Success).value != expected) {
                if (written is AppResult.Failed) return written
                return unresolved()
            }
            confirmed++
        }
        return AppResult.Success(confirmed)
    }

    private suspend fun readOriginals(
        access: CollaborationSpaceLocalAccess,
        journal: List<CollaborationSpaceMessageReceipt>,
    ): AppResult<
        Pair<List<CollaborationSpaceMessageRecord>, List<CollaborationSpaceMessageCompletion>>
    > {
        val records = mutableListOf<CollaborationSpaceMessageRecord>()
        val archived = mutableListOf<CollaborationSpaceMessageCompletion>()
        for (receipt in journal) {
            val read = messages.get(access, receipt.intent.id)
            admit(access).let { if (it is AppResult.Failed) return it }
            if (read is AppResult.Failed) return read
            val record = (read as AppResult.Success).value ?: return unresolved()
            if (
                record.intent != receipt.intent ||
                    !validCollaborationSpaceMessageRecord(access, record)
            )
                return unresolved()
            records += record
            if (record.state !is CollaborationMessageState.Sent) continue
            val completed = completions.readCompletion(access, record.intent.id)
            val detached =
                when (completed) {
                    is AppResult.Failed -> completed
                    is AppResult.Success -> {
                        val value = completed.value
                        if (
                            value != null &&
                                !validCollaborationSpaceMessageCompletion(access, value)
                        )
                            unresolved()
                        else AppResult.Success(value?.copy(eventIds = value.eventIds.toList()))
                    }
                }
            admit(access).let { if (it is AppResult.Failed) return it }
            if (detached is AppResult.Failed) return detached
            (detached as AppResult.Success).value?.let { archived += it }
        }
        return AppResult.Success(records.toList() to archived.toList())
    }

    private suspend fun admit(access: CollaborationSpaceLocalAccess): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val captured = sessions.current()
        currentCoroutineContext().ensureActive()
        if (captured is AppResult.Failed) return captured
        val owned =
            localCollaborationSpaceAccess(
                access,
                (captured as AppResult.Success).value,
                server.audience,
            )
        if (owned is AppResult.Failed) return owned
        val read = directory.admission(access)
        currentCoroutineContext().ensureActive()
        val latest = sessions.current()
        currentCoroutineContext().ensureActive()
        if (read is AppResult.Success) {
            if (!validCollaborationSpaceDirectoryAdmission(read.value)) return unresolved()
            read.value.denial?.let {
                return AppResult.Failed(it)
            }
        }
        if (latest is AppResult.Failed) return latest
        val current =
            localCollaborationSpaceAccess(
                access,
                (latest as AppResult.Success).value,
                server.audience,
            )
        if (current is AppResult.Failed) return current
        return if (read is AppResult.Failed) read else AppResult.Success(Unit)
    }

    private fun unresolved(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_space_receipt_unresolved", kind)
}
