package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/**
 * Bounded local inspection of both ledgers; never prepares, resumes, settles or enqueues a send.
 */
class ReadCollaborationMixedOriginalQueue(
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val runtimes: CollaborationSpaceRuntimeRepository,
    private val companyMessages: CollaborationMessageRepository,
    private val companyCompletions: CollaborationMessageCompletionRepository,
    private val spaceMessages: CollaborationSpaceMessageRepository,
    private val spaceCompletions: CollaborationSpaceMessageCompletionRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
    ): AppResult<CollaborationMixedOriginalQueueMatch> =
        withTimeoutOrNull(15_000) {
            if (!validCollaborationSpaceConversation(conversation, runtime.owner))
                return@withTimeoutOrNull changed()
            val admitted = guard(runtime.owner)
            if (admitted is AppResult.Failed) return@withTimeoutOrNull admitted
            val result = read(runtime, conversation)
            val current = guard(runtime.owner)
            if (current is AppResult.Failed) current else result
        }
            ?: collaborationRecoveryFailure(
                "client.collaboration_mixed_queue_timeout",
                FailureKind.Timeout,
            )

    private suspend fun read(
        runtime: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
    ): AppResult<CollaborationMixedOriginalQueueMatch> {
        val retained =
            bindings.getByConversation(runtime.owner.localDirectoryAccess(), conversation.id)
        val afterBinding = guard(runtime.owner)
        if (afterBinding is AppResult.Failed) return afterBinding
        if (retained is AppResult.Failed) return retained
        val binding = (retained as AppResult.Success).value ?: return changed()
        if (
            !validCollaborationSpaceRoomBinding(runtime.owner.localDirectoryAccess(), binding) ||
                binding.conversation != conversation.localMetadata()
        )
            return changed()
        val captured = runtimes.inspectOriginalQueue(runtime, binding.room.roomId)
        val snapshot =
            (captured as? AppResult.Success)
                ?.value
                ?.takeIf { it.original.messages.size <= 128 }
                ?.let {
                    it.copy(original = it.original.copy(messages = it.original.messages.toList()))
                }
        val afterCapture = guard(runtime.owner)
        if (afterCapture is AppResult.Failed) return afterCapture
        if (captured is AppResult.Failed) return captured
        if (snapshot == null) return changed()
        val originalAccess =
            collaborationMixedQueueCompanyAccess(runtime, conversation, binding, snapshot)
        if (originalAccess is AppResult.Failed) return originalAccess
        val matched =
            matchMessages(
                runtime,
                conversation,
                binding,
                snapshot,
                (originalAccess as AppResult.Success).value,
            )
        if (matched is AppResult.Failed) return matched
        val current = verifyCapture(runtime, conversation, binding, snapshot)
        return if (current is AppResult.Failed) current else matched
    }

    private suspend fun matchMessages(
        runtime: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
        binding: CollaborationSpaceRoomBinding,
        snapshot: CollaborationSpaceOriginalQueueSnapshot,
        companyAccess: CollaborationLocalAccess?,
    ): AppResult<CollaborationMixedOriginalQueueMatch> {
        val company = mutableListOf<CollaborationMatchedQueueMessage.Company>()
        val space = mutableListOf<CollaborationMatchedQueueMessage.Space>()
        for (original in snapshot.original.messages) {
            val old =
                if (companyAccess == null) AppResult.Success(null)
                else readCompanyOriginal(runtime.owner, companyAccess, original)
            if (old is AppResult.Failed) return old
            val next = readSpaceOriginal(runtime.owner, original)
            if (next is AppResult.Failed) return next
            val oldValue = (old as AppResult.Success).value
            val nextValue = (next as AppResult.Success).value
            if ((oldValue == null) == (nextValue == null)) return changed()
            oldValue?.let { company += it }
            nextValue?.let { space += it }
        }
        return matchCollaborationMixedOriginalQueue(
            runtime,
            conversation,
            binding,
            snapshot,
            company.map { it.record },
            company.mapNotNull { it.completion },
            space.map { it.record },
            space.mapNotNull { it.completion },
        )
    }

    private suspend fun readCompanyOriginal(
        owner: CollaborationSpaceOwner,
        access: CollaborationLocalAccess,
        original: CollaborationQueuedOriginal,
    ): AppResult<CollaborationMatchedQueueMessage.Company?> {
        val id = requireNotNull(original.logicalId)
        val read = companyMessages.get(access, id)
        val afterRecord = guard(owner)
        if (afterRecord is AppResult.Failed) return afterRecord
        if (read is AppResult.Failed) return read
        val record = (read as AppResult.Success).value ?: return AppResult.Success(null)
        if (record.intent.id != id) return changed()
        var completion: CollaborationMessageCompletion? = null
        if (record.state is CollaborationMessageState.Sent) {
            val received = companyCompletions.readCompletion(access, id)
            val proof =
                (received as? AppResult.Success)
                    ?.value
                    ?.takeIf { it.eventIds.size in 1..128 }
                    ?.let { it.copy(eventIds = it.eventIds.toList()) }
            val afterCompletion = guard(owner)
            if (afterCompletion is AppResult.Failed) return afterCompletion
            if (received is AppResult.Failed) return received
            completion = proof ?: return changed()
        }
        return AppResult.Success(
            CollaborationMatchedQueueMessage.Company(original, record, completion)
        )
    }

    private suspend fun readSpaceOriginal(
        owner: CollaborationSpaceOwner,
        original: CollaborationQueuedOriginal,
    ): AppResult<CollaborationMatchedQueueMessage.Space?> {
        val id = requireNotNull(original.logicalId)
        val access = owner.localDirectoryAccess()
        val read = spaceMessages.get(access, id)
        val afterRecord = guard(owner)
        if (afterRecord is AppResult.Failed) return afterRecord
        if (read is AppResult.Failed) return read
        val record = (read as AppResult.Success).value ?: return AppResult.Success(null)
        if (record.intent.id != id) return changed()
        var completion: CollaborationSpaceMessageCompletion? = null
        if (record.state is CollaborationMessageState.Sent) {
            val received = spaceCompletions.readCompletion(access, id)
            val proof =
                (received as? AppResult.Success)
                    ?.value
                    ?.takeIf { it.eventIds.size in 1..128 }
                    ?.let { it.copy(eventIds = it.eventIds.toList()) }
            val afterCompletion = guard(owner)
            if (afterCompletion is AppResult.Failed) return afterCompletion
            if (received is AppResult.Failed) return received
            completion = proof ?: return changed()
        }
        return AppResult.Success(
            CollaborationMatchedQueueMessage.Space(original, record, completion)
        )
    }

    private suspend fun verifyCapture(
        runtime: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
        binding: CollaborationSpaceRoomBinding,
        snapshot: CollaborationSpaceOriginalQueueSnapshot,
    ): AppResult<Unit> {
        val retained =
            bindings.getByConversation(runtime.owner.localDirectoryAccess(), conversation.id)
        val afterBinding = guard(runtime.owner)
        if (afterBinding is AppResult.Failed) return afterBinding
        if (retained is AppResult.Failed) return retained
        if ((retained as AppResult.Success).value != binding) return changed()
        val captured = runtimes.inspectOriginalQueue(runtime, binding.room.roomId)
        val latest =
            (captured as? AppResult.Success)
                ?.value
                ?.takeIf { it.original.messages.size <= 128 }
                ?.let {
                    it.copy(original = it.original.copy(messages = it.original.messages.toList()))
                }
        val afterCapture = guard(runtime.owner)
        if (afterCapture is AppResult.Failed) return afterCapture
        if (captured is AppResult.Failed) return captured
        if (latest != snapshot) return changed()
        val open = runtimes.isSpaceOpen(runtime.owner, runtime.deviceId, runtime.operationId)
        val afterOpen = guard(runtime.owner)
        if (afterOpen is AppResult.Failed) return afterOpen
        if (open is AppResult.Failed) return open
        return if ((open as AppResult.Success).value) AppResult.Success(Unit) else changed()
    }

    private suspend fun guard(owner: CollaborationSpaceOwner): AppResult<Unit> {
        val local = sessions.guardSpaceRecoveryOwner(server, owner)
        if (local is AppResult.Failed) return local
        val admitted = directory.admission(owner.localDirectoryAccess())
        currentCoroutineContext().ensureActive()
        if (admitted is AppResult.Failed) return admitted
        val value = (admitted as AppResult.Success).value
        if (!validCollaborationSpaceDirectoryAdmission(value)) return changed()
        value.denial?.let {
            return AppResult.Failed(it)
        }
        return sessions.guardSpaceRecoveryOwner(server, owner)
    }

    private fun changed() =
        collaborationRecoveryFailure(
            "client.collaboration_mixed_queue_changed",
            FailureKind.Conflict,
        )
}
