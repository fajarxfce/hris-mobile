package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** One finite local transfer. Setup and the existing inbox producer have separate owners. */
class TransferCollaborationSpaceRecipientSubmission(
    private val outbox: CollaborationSpaceRecipientOutboxRepository,
    private val messages: CollaborationSpaceMessageRepository,
    private val metadata: CollaborationSpaceConversationMetadataRepository,
    private val inbox: CollaborationSpaceInboxRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceRecipientSubmission,
    ): AppResult<CollaborationSpaceRecipientTransfer> {
        if (
            !validCollaborationSpaceRecipientSubmission(expected) ||
                !validCollaborationSpaceRecipientCoordinates(
                    access,
                    expected.original.peerAccountId,
                )
        )
            return invalidDraft()
        return withTimeoutOrNull(5_000) { Transfer(access, expected).execute() }
            ?: collaborationRecoveryFailure(
                "client.collaboration_composer_recovery_timeout",
                FailureKind.Timeout,
            )
    }

    /** Captured source, immutable destination and denial latch belong to this invocation alone. */
    private inner class Transfer(
        val access: CollaborationSpaceLocalAccess,
        val expected: CollaborationSpaceRecipientSubmission,
    ) {
        var denial: Failure? = null

        suspend fun execute(): AppResult<CollaborationSpaceRecipientTransfer> {
            admitted().let { if (it is AppResult.Failed) return it }
            val source = outbox.get(access, expected.id)
            admitted().let { if (it is AppResult.Failed) return it }
            if (source is AppResult.Failed) return source
            val retained = (source as AppResult.Success).value
            if (retained != null && retained != expected) return changedDraft()
            val found = materialize(retained != null)
            if (found is AppResult.Failed) return found
            val record =
                (found as AppResult.Success).value
                    ?: return AppResult.Success(
                        CollaborationSpaceRecipientTransfer.WaitingForConversation(null)
                    )
            val read = metadata.getById(access, record.intent.conversationId)
            admitted().let { if (it is AppResult.Failed) return it }
            if (read is AppResult.Failed) return read
            val conversation =
                (read as AppResult.Success).value
                    ?: return AppResult.Success(
                        CollaborationSpaceRecipientTransfer.WaitingForConversation(
                            record.intent.conversationId
                        )
                    )
            if (
                conversation.id != record.intent.conversationId ||
                    !validCollaborationSpaceRecipientConversation(access, expected, conversation)
            )
                return invalidResponse()
            return confirmInbox(record, conversation)
        }

        private suspend fun materialize(
            canInsert: Boolean
        ): AppResult<CollaborationSpaceMessageRecord?> {
            val existing = messages.get(access, expected.id)
            admitted().let { if (it is AppResult.Failed) return it }
            if (existing is AppResult.Failed) return existing
            (existing as AppResult.Success).value?.let {
                return if (matchesCollaborationSpaceRecipientSubmission(access, it, expected))
                    AppResult.Success(it)
                else changedDraft()
            }
            // An absent source never grants another insertion, even if its peer is known.
            if (!canInsert) return changedDraft()
            val loaded = metadata.findByPeer(access, expected.original.peerAccountId)
            admitted().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            val conversation = (loaded as AppResult.Success).value ?: return AppResult.Success(null)
            if (!validCollaborationSpaceRecipientConversation(access, expected, conversation))
                return invalidResponse()
            val intent = collaborationSpaceRecipientMessageIntent(access, expected, conversation.id)
            currentCoroutineContext().ensureActive()
            val inserted = messages.insert(access, intent)
            admitted().let { if (it is AppResult.Failed) return it }
            val interrupted = (inserted as? AppResult.Failed)?.failure
            if (
                interrupted != null &&
                    interrupted.kind !in setOf(FailureKind.Storage, FailureKind.Conflict)
            )
                return AppResult.Failed(interrupted)
            if (
                (inserted as? AppResult.Success)?.value ==
                    CollaborationMessageInsertion.SCOPE_CHANGED
            )
                return collaborationRecoveryFailure(
                    "client.session_changed",
                    FailureKind.Unauthorized,
                )
            val readback = messages.get(access, expected.id)
            admitted().let { if (it is AppResult.Failed) return it }
            if (readback is AppResult.Failed) return readback
            val record = (readback as AppResult.Success).value
            if (record != null) {
                // A competing valid proposal may have won another canonical target. Its immutable
                // recipient proof is authoritative; never insert the same ID into another room.
                return if (matchesCollaborationSpaceRecipientSubmission(access, record, expected))
                    AppResult.Success(record)
                else changedDraft()
            }
            if (interrupted != null) return AppResult.Failed(interrupted)
            return if (
                (inserted as AppResult.Success).value ==
                    CollaborationMessageInsertion.CAPACITY_EXCEEDED
            )
                collaborationRecoveryFailure("client.command_queue_full", FailureKind.Storage)
            else changedDraft()
        }

        private suspend fun confirmInbox(
            record: CollaborationSpaceMessageRecord,
            conversation: CollaborationSpaceConversationMetadata,
        ): AppResult<CollaborationSpaceRecipientTransfer> {
            repeat(3) {
                admitted().let { if (it is AppResult.Failed) return it }
                val current = inbox.conversation(access, conversation.id, candidateLimit = 2)
                admitted().let { if (it is AppResult.Failed) return it }
                if (current is AppResult.Failed) return current
                val anchors =
                    inbox.activities(
                        access,
                        conversation.id,
                        listOf(CollaborationSpaceInboxSource.GroupOriginal(expected.id)),
                    )
                admitted().let { if (it is AppResult.Failed) return it }
                if (anchors is AppResult.Failed) return anchors
                val index = (current as AppResult.Success).value
                val exact = (anchors as AppResult.Success).value
                if (index.version != exact.version) return@repeat
                val prepared =
                    collaborationSpaceInboxSeedPublication(
                        access,
                        conversation,
                        record,
                        index,
                        exact,
                    )
                if (prepared is AppResult.Failed) return prepared
                // Null repair is a semantic probe. The existing producer owns index writes/demand.
                val probe = inbox.seed(access, (prepared as AppResult.Success).value, null)
                admitted().let { if (it is AppResult.Failed) return it }
                if (probe is AppResult.Failed) return probe
                when ((probe as AppResult.Success).value) {
                    CollaborationSpaceInboxSeedResult.RETAINED -> return retire(conversation.id)
                    CollaborationSpaceInboxSeedResult.DEMAND_REQUIRED ->
                        return AppResult.Success(
                            CollaborationSpaceRecipientTransfer.WaitingForInbox(conversation.id)
                        )
                    CollaborationSpaceInboxSeedResult.CHANGED -> Unit
                    CollaborationSpaceInboxSeedResult.STORED -> return invalidResponse()
                }
            }
            return collaborationRecoveryFailure(
                "client.collaboration_inbox_busy",
                FailureKind.Conflict,
            )
        }

        private suspend fun retire(
            conversationId: String
        ): AppResult<CollaborationSpaceRecipientTransfer> {
            currentCoroutineContext().ensureActive()
            val removed = outbox.remove(access, expected)
            admitted().let { if (it is AppResult.Failed) return it }
            val interrupted = (removed as? AppResult.Failed)?.failure
            if (
                interrupted != null &&
                    interrupted.kind !in setOf(FailureKind.Storage, FailureKind.Conflict)
            )
                return AppResult.Failed(interrupted)
            val remaining = outbox.get(access, expected.id)
            admitted().let { if (it is AppResult.Failed) return it }
            if (remaining is AppResult.Failed)
                return interrupted?.let { AppResult.Failed(it) } ?: remaining
            if ((remaining as AppResult.Success).value == null)
                return AppResult.Success(
                    CollaborationSpaceRecipientTransfer.Transferred(conversationId)
                )
            return interrupted?.let { AppResult.Failed(it) } ?: changedDraft()
        }

        private suspend fun admitted(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            denial?.let {
                return AppResult.Failed(it)
            }
            val identity = sessions.current()
            currentCoroutineContext().ensureActive()
            if (identity is AppResult.Failed) return identity
            val owned =
                localCollaborationSpaceAccess(
                    access,
                    (identity as AppResult.Success).value,
                    server.audience,
                )
            if (owned is AppResult.Failed) return owned
            val read = directory.admission(access)
            currentCoroutineContext().ensureActive()
            if (read is AppResult.Failed) return read
            val gate = (read as AppResult.Success).value
            if (!validCollaborationSpaceDirectoryAdmission(gate)) return invalidResponse()
            denial = gate.denial
            denial?.let {
                return AppResult.Failed(it)
            }
            val latest = sessions.current()
            currentCoroutineContext().ensureActive()
            if (latest is AppResult.Failed) return latest
            val current =
                localCollaborationSpaceAccess(
                    access,
                    (latest as AppResult.Success).value,
                    server.audience,
                )
            return if (current is AppResult.Failed) current else AppResult.Success(Unit)
        }

        private fun invalidResponse() =
            collaborationRecoveryFailure(
                "client.collaboration_invalid_response",
                FailureKind.Unexpected,
            )
    }
}
