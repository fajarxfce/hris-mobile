package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** True needs original recovery; false proves a locally idle room without a transport read. */
class ReadCollaborationSpaceRecoveryDemand(
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val runtimes: CollaborationSpaceRuntimeRepository,
    private val messages: CollaborationMessageRepository,
    private val receipts: CollaborationReceiptRepository,
    private val completions: CollaborationMessageCompletionRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
    ): AppResult<Boolean> =
        withTimeoutOrNull(5_000) {
            if (
                runtime.owner != conversation.owner ||
                    !validCollaborationSpaceConversation(conversation)
            )
                return@withTimeoutOrNull invalidCollaborationSpaceRecoveryDemand()
            val admitted = guard(runtime.owner)
            if (admitted is AppResult.Failed) return@withTimeoutOrNull admitted
            val result = read(runtime, conversation)
            val current = guard(runtime.owner)
            if (current is AppResult.Failed) current else result
        }
            ?: collaborationRecoveryFailure(
                "client.collaboration_space_recovery_demand_timeout",
                FailureKind.Timeout,
            )

    private suspend fun read(
        runtime: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
    ): AppResult<Boolean> {
        val retained =
            bindings.getByConversation(runtime.owner.localDirectoryAccess(), conversation.id)
        if (retained is AppResult.Failed) return retained
        val binding =
            (retained as AppResult.Success).value
                ?: return invalidCollaborationSpaceRecoveryDemand()
        if (
            !validCollaborationSpaceRoomBinding(runtime.owner.localDirectoryAccess(), binding) ||
                binding.conversation != conversation.localMetadata() ||
                binding.original == null
        )
            return invalidCollaborationSpaceRecoveryDemand()
        val captured = runtimes.inspectOriginalQueue(runtime, binding.room.roomId)
        if (captured is AppResult.Failed) return captured
        val rawSnapshot = (captured as AppResult.Success).value
        if (rawSnapshot.original.messages.size > 128)
            return invalidCollaborationSpaceRecoveryDemand()
        val snapshot =
            rawSnapshot.copy(
                original =
                    rawSnapshot.original.copy(messages = rawSnapshot.original.messages.toList())
            )
        val checked =
            collaborationSpaceRecoveryQueueAccess(runtime, conversation, binding, snapshot)
        if (checked is AppResult.Failed) return checked
        if (snapshot.original.messages.isNotEmpty()) return AppResult.Success(true)
        val access = (checked as AppResult.Success).value
        val originalId = requireNotNull(binding.original).conversation.position.conversationId
        val demand = readLedger(access, originalId)
        if (demand is AppResult.Failed || (demand as AppResult.Success).value) return demand
        val last = runtimes.inspectOriginalQueue(runtime, binding.room.roomId)
        if (last is AppResult.Failed) return last
        val finalSnapshot = (last as AppResult.Success).value
        val finalAccess =
            collaborationSpaceRecoveryQueueAccess(runtime, conversation, binding, finalSnapshot)
        if (finalAccess is AppResult.Failed) return finalAccess
        if (finalSnapshot != snapshot) return AppResult.Success(true)
        val open = runtimes.isSpaceOpen(runtime.owner, runtime.deviceId, runtime.operationId)
        if (open is AppResult.Failed) return open
        return if ((open as AppResult.Success).value) AppResult.Success(false)
        else invalidCollaborationSpaceRecoveryDemand()
    }

    private suspend fun readLedger(
        access: CollaborationLocalAccess,
        originalId: String,
    ): AppResult<Boolean> {
        val observed = messages.observe(access, originalId).first()
        if (observed is AppResult.Failed) return observed
        val rawRecords = (observed as AppResult.Success).value
        if (
            rawRecords.size > 128 ||
                rawRecords.any {
                    !validCollaborationMessageState(it.state) || !validCollaborationEditRecord(it)
                }
        )
            return invalidCollaborationSpaceRecoveryDemand()
        val records = rawRecords.toList()
        val received = receipts.read(access, originalId)
        if (received is AppResult.Failed) return received
        val raw = (received as AppResult.Success).value
        if (!validCollaborationReceiptLedger(raw)) return invalidCollaborationSpaceRecoveryDemand()
        val journal = raw.map { it.copy(eventIds = it.eventIds.toList()) }
        val missing =
            missingCollaborationReceiptIds(access, originalId, records, journal)
                ?: return invalidCollaborationSpaceRecoveryDemand()
        if (
            records.any {
                it.state !is CollaborationMessageState.Sent &&
                    it.state != CollaborationMessageState.Cancelled
            }
        )
            return AppResult.Success(true)
        val ids =
            (missing +
                    records
                        .filter { it.state is CollaborationMessageState.Sent }
                        .map { it.intent.id })
                .distinct()
        val completed = readCompletions(access, originalId, ids)
        if (completed is AppResult.Failed) return completed
        val archived = (completed as AppResult.Success).value
        if (!collaborationSpaceRecoveryLedgerIdle(records, journal, archived))
            return AppResult.Success(true)
        // Recheck retained local work after exact completion reads; never create an ACK.
        val latest = messages.observe(access, originalId).first()
        if (latest is AppResult.Failed) return latest
        if ((latest as AppResult.Success).value != records) return AppResult.Success(true)
        val latestReceipts = receipts.read(access, originalId)
        if (latestReceipts is AppResult.Failed) return latestReceipts
        if ((latestReceipts as AppResult.Success).value != journal) return AppResult.Success(true)
        return AppResult.Success(false)
    }

    private suspend fun readCompletions(
        access: CollaborationLocalAccess,
        originalId: String,
        ids: List<String>,
    ): AppResult<List<CollaborationMessageCompletion>> {
        val archived = mutableListOf<CollaborationMessageCompletion>()
        for (id in ids) {
            val completion = completions.readCompletion(access, id)
            if (completion is AppResult.Failed) return completion
            val proof = (completion as AppResult.Success).value ?: continue
            if (
                proof.record.intent.id != id ||
                    proof.eventIds.size !in 1..128 ||
                    proof.record.intent.owner != access.messageOwner() ||
                    proof.record.intent.conversationId != originalId
            )
                return invalidCollaborationSpaceRecoveryDemand()
            archived += proof.copy(eventIds = proof.eventIds.toList())
        }
        return AppResult.Success(archived)
    }

    private suspend fun guard(owner: CollaborationSpaceOwner): AppResult<Unit> {
        val local = sessions.guardSpaceRecoveryOwner(server, owner)
        if (local is AppResult.Failed) return local
        val admitted = directory.admission(owner.localDirectoryAccess())
        currentCoroutineContext().ensureActive()
        if (admitted is AppResult.Failed) return admitted
        val value = (admitted as AppResult.Success).value
        if (!validCollaborationSpaceDirectoryAdmission(value))
            return invalidCollaborationSpaceRecoveryDemand()
        value.denial?.let {
            return AppResult.Failed(it)
        }
        return sessions.guardSpaceRecoveryOwner(server, owner)
    }
}
