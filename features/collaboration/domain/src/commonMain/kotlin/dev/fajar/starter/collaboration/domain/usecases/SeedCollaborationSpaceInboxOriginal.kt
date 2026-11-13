@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One retained original, owned locally; delivery and continuous observation remain separate. */
class SeedCollaborationSpaceInboxOriginal(
    private val messages: CollaborationSpaceMessageRepository,
    private val metadata: CollaborationSpaceConversationMetadataRepository,
    private val inbox: CollaborationSpaceInboxRepository,
    private val progress: CollaborationSpaceInboxRepairProgressRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        intent: CollaborationSpaceMessageIntent,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationSpaceMessageIntent(access, intent) || intent.editTarget != null)
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_message",
                FailureKind.Validation,
            )
        return Seed(access, intent).execute()
    }

    /** Serial bounded attempts; a replay never acquires or advances repair progress. */
    private inner class Seed(
        val access: CollaborationSpaceLocalAccess,
        val intent: CollaborationSpaceMessageIntent,
    ) {
        var denial: Failure? = null
        val demand = Uuid.random().toString()

        suspend fun execute(): AppResult<Boolean> {
            repeat(3) {
                admitted().let { if (it is AppResult.Failed) return it }
                val read = messages.get(access, intent.id)
                admitted().let { if (it is AppResult.Failed) return it }
                if (read is AppResult.Failed) return read
                val retained = (read as AppResult.Success).value ?: return AppResult.Success(false)
                val record =
                    collaborationSpaceMessageInventory(access, listOf(retained))?.singleOrNull()
                if (record == null || record.intent != intent)
                    return collaborationRecoveryFailure(
                        "client.collaboration_invalid_response",
                        FailureKind.Unexpected,
                    )
                val canonical = metadata.getById(access, intent.conversationId)
                admitted().let { if (it is AppResult.Failed) return it }
                if (canonical is AppResult.Failed) return canonical
                val conversation =
                    (canonical as AppResult.Success).value
                        ?: return collaborationRecoveryFailure(
                            "client.collaboration_conversation_unavailable",
                            FailureKind.Unavailable,
                        )
                val local = inbox.conversation(access, intent.conversationId, candidateLimit = 2)
                admitted().let { if (it is AppResult.Failed) return it }
                if (local is AppResult.Failed) return local
                val anchors =
                    inbox.activities(
                        access,
                        intent.conversationId,
                        listOf(CollaborationSpaceInboxSource.GroupOriginal(intent.id)),
                    )
                admitted().let { if (it is AppResult.Failed) return it }
                if (anchors is AppResult.Failed) return anchors
                val index = (local as AppResult.Success).value
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
                val publication = (prepared as AppResult.Success).value
                currentCoroutineContext().ensureActive()
                val probe = inbox.seed(access, publication, null)
                admitted().let { if (it is AppResult.Failed) return it }
                if (probe is AppResult.Failed) return probe
                when ((probe as AppResult.Success).value) {
                    CollaborationSpaceInboxSeedResult.RETAINED -> return AppResult.Success(true)
                    CollaborationSpaceInboxSeedResult.CHANGED -> return@repeat
                    CollaborationSpaceInboxSeedResult.STORED ->
                        return collaborationRecoveryFailure(
                            "client.collaboration_invalid_response",
                            FailureKind.Unexpected,
                        )
                    CollaborationSpaceInboxSeedResult.DEMAND_REQUIRED -> Unit
                }
                val saved = publishWithRepairDemand(publication)
                if (saved is AppResult.Failed) return saved
                when ((saved as AppResult.Success).value) {
                    CollaborationSpaceInboxSeedResult.RETAINED,
                    CollaborationSpaceInboxSeedResult.STORED -> return AppResult.Success(true)
                    CollaborationSpaceInboxSeedResult.CHANGED -> Unit
                    CollaborationSpaceInboxSeedResult.DEMAND_REQUIRED ->
                        return collaborationRecoveryFailure(
                            "client.collaboration_invalid_response",
                            FailureKind.Unexpected,
                        )
                }
            }
            return collaborationRecoveryFailure(
                "client.collaboration_inbox_busy",
                FailureKind.Conflict,
            )
        }

        /** New work alone acquires a checkpoint and publishes the two partitions together. */
        private suspend fun publishWithRepairDemand(
            publication: CollaborationSpaceInboxPublication
        ): AppResult<CollaborationSpaceInboxSeedResult> {
            val checkpoint = progress.current(access)
            admitted().let { if (it is AppResult.Failed) return it }
            if (checkpoint is AppResult.Failed) return checkpoint
            val current = (checkpoint as AppResult.Success).value
            if (current != null && !validCollaborationSpaceInboxRepairProgress(current))
                return collaborationRecoveryFailure(
                    "client.collaboration_invalid_response",
                    FailureKind.Unexpected,
                )
            val requested =
                collaborationSpaceInboxRepairProgress(
                    current,
                    CollaborationSpaceInboxRepairProgressChange.Request(demand),
                    Uuid.random().toString(),
                )
            if (requested is AppResult.Failed) return requested
            val repair =
                CollaborationSpaceInboxRepairPublication(
                    current,
                    (requested as AppResult.Success).value,
                )
            currentCoroutineContext().ensureActive()
            val saved = inbox.seed(access, publication, repair)
            admitted().let { if (it is AppResult.Failed) return it }
            return saved
        }

        private suspend fun admitted(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val identity = sessions.current()
            currentCoroutineContext().ensureActive()
            if (identity is AppResult.Failed) return AppResult.Failed(denial ?: identity.failure)
            val owned =
                localCollaborationSpaceAccess(
                    access,
                    (identity as AppResult.Success).value,
                    server.audience,
                )
            if (owned is AppResult.Failed) return AppResult.Failed(denial ?: owned.failure)
            val read = directory.admission(access)
            currentCoroutineContext().ensureActive()
            if (read is AppResult.Failed) return AppResult.Failed(denial ?: read.failure)
            val gate = (read as AppResult.Success).value
            if (!validCollaborationSpaceDirectoryAdmission(gate))
                return denial?.let { AppResult.Failed(it) }
                    ?: collaborationRecoveryFailure(
                        "client.collaboration_invalid_response",
                        FailureKind.Unexpected,
                    )
            denial = denial ?: gate.denial
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
    }
}
