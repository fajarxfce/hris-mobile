package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit local send intent. This never acquires credentials, creates a room, or sends HTTP. */
class QueueCollaborationText(
    private val messages: CollaborationMessageRepository,
    private val conversations: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val schedules: SyncScheduleRepository,
) {
    private val submissions = Mutex()

    /** The caller retains id across a lost response; a new explicit send gets a new ID. */
    suspend operator fun invoke(
        workspaceId: String,
        conversationId: String,
        id: String,
        text: String,
        expectedAccess: CollaborationLocalAccess? = null,
    ): AppResult<CollaborationMessageRecord> =
        submissions.withLock {
            if (
                !validCollaborationMessageId(id) ||
                    !validCollaborationMessageId(conversationId) ||
                    !validCollaborationText(text)
            )
                return@withLock collaborationSetupFailure(
                    "client.collaboration_invalid_message",
                    FailureKind.Validation,
                )
            val captured = capture(workspaceId)
            if (captured is AppResult.Failed) return@withLock captured
            val access = (captured as AppResult.Success).value
            if (expectedAccess != null && access != expectedAccess)
                return@withLock collaborationSetupFailure(
                    "client.session_changed",
                    FailureKind.Unauthorized,
                )
            continuation(workspaceId, access).let { if (it is AppResult.Failed) return@withLock it }
            val retained = messages.get(access, id)
            continuation(workspaceId, access).let { if (it is AppResult.Failed) return@withLock it }
            if (retained is AppResult.Failed) return@withLock retained
            (retained as AppResult.Success).value?.let {
                return@withLock wake(
                    workspaceId,
                    access,
                    matching(it, access, conversationId, id, text),
                )
            }
            val acquired = conversations.get(access, conversationId)
            continuation(workspaceId, access).let { if (it is AppResult.Failed) return@withLock it }
            if (acquired is AppResult.Failed) return@withLock acquired
            val direct = (acquired as AppResult.Success).value?.conversation
            if (
                direct?.id != conversationId ||
                    direct.companyId != access.companyId ||
                    access.accountId !in listOf(direct.firstAccountId, direct.secondAccountId)
            )
                return@withLock collaborationSetupFailure(
                    "client.collaboration_conversation_unavailable",
                    FailureKind.Unavailable,
                )
            val intent =
                CollaborationMessageIntent(
                    id,
                    access.messageOwner(),
                    conversationId,
                    clock.now(),
                    text,
                )
            currentCoroutineContext().ensureActive()
            val inserted = messages.insert(access, intent)
            continuation(workspaceId, access).let { if (it is AppResult.Failed) return@withLock it }
            if (inserted is AppResult.Failed) return@withLock inserted
            when ((inserted as AppResult.Success).value) {
                CollaborationMessageInsertion.CAPACITY_EXCEEDED ->
                    return@withLock collaborationSetupFailure(
                        "client.command_queue_full",
                        FailureKind.Storage,
                    )
                CollaborationMessageInsertion.SCOPE_CHANGED ->
                    return@withLock collaborationSetupFailure(
                        "client.session_changed",
                        FailureKind.Unauthorized,
                    )
                CollaborationMessageInsertion.STORED -> Unit
            }
            val stored = messages.get(access, id)
            continuation(workspaceId, access).let { if (it is AppResult.Failed) return@withLock it }
            if (stored is AppResult.Failed) return@withLock stored
            (stored as AppResult.Success).value?.let {
                wake(workspaceId, access, matching(it, access, conversationId, id, text))
            } ?: collaborationSetupFailure("client.collaboration_message_changed")
        }

    /** Scheduler payloads contain only this key; the encrypted command is the durable work. */
    companion object {
        const val DELIVERY_KEY = "hris.collaboration-delivery"
    }

    private suspend fun wake(
        workspaceId: String,
        access: CollaborationLocalAccess,
        result: AppResult<CollaborationMessageRecord>,
    ): AppResult<CollaborationMessageRecord> {
        if (result is AppResult.Failed) return result
        val state = (result as AppResult.Success).value.state
        if (
            state !is CollaborationMessageState.Sent && state != CollaborationMessageState.Cancelled
        ) {
            // Failed scheduling cannot undo a committed intent. Startup/periodic recovery scans
            // durable commands. Cancellation still prevents a late UI acknowledgement.
            withTimeoutOrNull(1_500) { schedules.request(DELIVERY_KEY) }
            val current = continuation(workspaceId, access)
            if (current is AppResult.Failed) return current
        }
        return result
    }

    private fun matching(
        record: CollaborationMessageRecord,
        access: CollaborationLocalAccess,
        conversationId: String,
        id: String,
        text: String,
        expectedAccess: CollaborationLocalAccess? = null,
    ): AppResult<CollaborationMessageRecord> =
        if (
            record.intent.owner == access.messageOwner() &&
                record.intent.conversationId == conversationId &&
                record.intent.id == id &&
                record.intent.text == text
        )
            AppResult.Success(record)
        else collaborationSetupFailure("client.collaboration_message_changed")

    private suspend fun capture(workspaceId: String) =
        sessions.captureMessageAccess(workspaceId, workspaces, server, build, clock)

    private suspend fun continuation(
        workspaceId: String,
        access: CollaborationLocalAccess,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = conversations.admission(access)
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
}
