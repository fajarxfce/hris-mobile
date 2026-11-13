package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One exact retained reference. True means its terminal progress and completion marker are stored.
 */
class ReconcileCollaborationSpaceInboxOriginal(
    private val messages: CollaborationSpaceMessageRepository,
    private val inbox: CollaborationSpaceInboxRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        reference: CollaborationSpaceInboxRepairReference,
    ): AppResult<Boolean> {
        val checked = captureCollaborationSpaceInboxCommand(access, reference, null)
        return if (checked is AppResult.Failed) checked
        else Reconciliation(access, reference).execute()
    }

    /** The caller owns bounded contention attempts and a denial that survives failed reads. */
    private inner class Reconciliation(
        val access: CollaborationSpaceLocalAccess,
        val reference: CollaborationSpaceInboxRepairReference,
    ) {
        var denial: Failure? = null

        suspend fun execute(): AppResult<Boolean> {
            repeat(3) {
                admitted().let { if (it is AppResult.Failed) return it }
                val read = messages.get(access, reference.intent.id)
                admitted().let { if (it is AppResult.Failed) return it }
                if (read is AppResult.Failed) return read
                val captured =
                    captureCollaborationSpaceInboxCommand(
                        access,
                        reference,
                        (read as AppResult.Success).value,
                    )
                if (captured is AppResult.Failed) return captured
                val record =
                    (captured as AppResult.Success).value ?: return AppResult.Success(false)
                if (
                    record.state !is CollaborationMessageState.Sent &&
                        record.state != CollaborationMessageState.Cancelled
                )
                    return AppResult.Success(false)
                val conversationId = reference.intent.conversationId
                val index = inbox.conversation(access, conversationId, candidateLimit = 2)
                admitted().let { if (it is AppResult.Failed) return it }
                if (index is AppResult.Failed) return index
                val anchor =
                    inbox.activities(
                        access,
                        conversationId,
                        listOf(CollaborationSpaceInboxSource.GroupOriginal(reference.intent.id)),
                    )
                admitted().let { if (it is AppResult.Failed) return it }
                if (anchor is AppResult.Failed) return anchor
                val current = (index as AppResult.Success).value
                val anchors = (anchor as AppResult.Success).value
                if (current.version != anchors.version) return@repeat
                val prepared =
                    collaborationSpaceInboxCommandPublication(
                        access,
                        reference,
                        record,
                        current,
                        anchors,
                    )
                if (prepared is AppResult.Failed) return prepared
                currentCoroutineContext().ensureActive()
                val saved = inbox.publish(access, (prepared as AppResult.Success).value)
                admitted().let { if (it is AppResult.Failed) return it }
                if (saved is AppResult.Failed) return saved
                if ((saved as AppResult.Success).value) return AppResult.Success(true)
            }
            return collaborationRecoveryFailure(
                "client.collaboration_inbox_busy",
                FailureKind.Conflict,
            )
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
