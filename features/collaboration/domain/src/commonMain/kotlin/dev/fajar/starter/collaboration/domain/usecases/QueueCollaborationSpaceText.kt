package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Local intent only. Live device admission and provider delivery have separate owners. */
class QueueCollaborationSpaceText(
    private val messages: CollaborationSpaceMessageRepository,
    private val conversations: CollaborationSpaceConversationMetadataRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val schedules: SyncScheduleRepository,
) {
    private val submissions = Mutex()

    /**
     * Capture once before presentation/suspension; preserve the full original through every retry.
     */
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        intent: CollaborationSpaceMessageIntent,
    ): AppResult<CollaborationSpaceMessageRecord> =
        submissions.withLock {
            if (
                !validCollaborationSpaceMessageIntent(access, intent) ||
                    intent.forwarded ||
                    intent.editTarget != null
            )
                return@withLock collaborationRecoveryFailure(
                    "client.collaboration_invalid_message",
                    FailureKind.Validation,
                )
            val id = intent.id
            val conversationId = intent.conversationId
            var denial: Failure? = null
            suspend fun admitted(): AppResult<Unit> {
                currentCoroutineContext().ensureActive()
                val session = sessions.current()
                currentCoroutineContext().ensureActive()
                if (session is AppResult.Failed) return AppResult.Failed(denial ?: session.failure)
                val owned =
                    localCollaborationSpaceAccess(
                        access,
                        (session as AppResult.Success).value,
                        server.audience,
                    )
                if (owned is AppResult.Failed) return AppResult.Failed(denial ?: owned.failure)
                val read = directory.admission(access)
                currentCoroutineContext().ensureActive()
                if (read is AppResult.Failed) return AppResult.Failed(denial ?: read.failure)
                val gate = (read as AppResult.Success).value
                if (!validCollaborationSpaceDirectoryAdmission(gate))
                    return AppResult.Failed(denial ?: invalid().failure)
                denial = denial ?: gate.denial
                // Admission storage can suspend while the local account is replaced.
                val latest = sessions.current()
                currentCoroutineContext().ensureActive()
                denial?.let {
                    return AppResult.Failed(it)
                }
                if (latest is AppResult.Failed) return latest
                val current =
                    localCollaborationSpaceAccess(
                        access,
                        (latest as AppResult.Success).value,
                        server.audience,
                    )
                return if (current is AppResult.Failed) current else AppResult.Success(Unit)
            }
            suspend fun confirmed(
                record: CollaborationSpaceMessageRecord
            ): AppResult<CollaborationSpaceMessageRecord> {
                if (
                    !validCollaborationSpaceMessageRecord(access, record) || record.intent != intent
                )
                    return collaborationRecoveryFailure(
                        "client.collaboration_message_changed",
                        FailureKind.Conflict,
                    )
                if (
                    record.state !is CollaborationMessageState.Sent &&
                        record.state != CollaborationMessageState.Cancelled
                ) {
                    // The transaction retained the wake. A failed hint cannot undo the message.
                    withTimeoutOrNull(1_500) {
                        schedules.request(CollaborationSpaceRecoveryWake.WORK_KEY)
                    }
                    val current = admitted()
                    if (current is AppResult.Failed) return current
                }
                return AppResult.Success(record)
            }

            admitted().let { if (it is AppResult.Failed) return@withLock it }
            val retained = messages.get(access, id)
            admitted().let { if (it is AppResult.Failed) return@withLock it }
            if (retained is AppResult.Failed) return@withLock retained
            (retained as AppResult.Success).value?.let {
                return@withLock confirmed(it)
            }

            val canonical = conversations.getById(access, conversationId)
            admitted().let { if (it is AppResult.Failed) return@withLock it }
            if (canonical is AppResult.Failed) return@withLock canonical
            val conversation = (canonical as AppResult.Success).value
            if (
                conversation == null ||
                    conversation.id != conversationId ||
                    !validCollaborationSpaceConversationMetadata(access, conversation)
            )
                return@withLock collaborationRecoveryFailure(
                    "client.collaboration_conversation_unavailable",
                    FailureKind.Unavailable,
                )
            currentCoroutineContext().ensureActive()
            val written = messages.insert(access, intent)
            admitted().let { if (it is AppResult.Failed) return@withLock it }
            // A failed response can follow a committed transaction. Never allocate another ID.
            val readback = messages.get(access, id)
            admitted().let { if (it is AppResult.Failed) return@withLock it }
            if (readback is AppResult.Failed) return@withLock readback
            (readback as AppResult.Success).value?.let {
                return@withLock confirmed(it)
            }
            if (written is AppResult.Failed) return@withLock written
            when ((written as AppResult.Success).value) {
                CollaborationMessageInsertion.CAPACITY_EXCEEDED ->
                    collaborationRecoveryFailure("client.command_queue_full", FailureKind.Storage)
                CollaborationMessageInsertion.SCOPE_CHANGED ->
                    collaborationRecoveryFailure("client.session_changed", FailureKind.Unauthorized)
                CollaborationMessageInsertion.STORED -> invalid()
            }
        }

    private fun invalid() =
        collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
}
