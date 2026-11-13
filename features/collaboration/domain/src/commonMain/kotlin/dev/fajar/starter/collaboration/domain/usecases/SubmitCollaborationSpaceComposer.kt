package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlinx.coroutines.*

/** Persist one captured gesture. Delivery and its native runtime have separate owners. */
class SubmitCollaborationSpaceComposer(
    private val outbox: CollaborationSpaceComposerOutboxRepository,
    private val drafts: CollaborationSpaceDraftRepository,
    private val inbox: CollaborationSpaceInboxRepository,
    private val messages: CollaborationSpaceMessageRepository,
    private val metadata: CollaborationSpaceConversationMetadataRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val schedules: SyncScheduleRepository,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationDraft?,
        submission: CollaborationComposerSubmission,
    ): AppResult<CollaborationComposerSubmission> =
        if (
            !validCollaborationComposerHandoff(submission, expected) ||
                !validCollaborationSpaceMessageIntent(
                    access,
                    collaborationSpaceComposerIntent(access, submission),
                )
        )
            invalidDraft()
        else Submission(access, expected, submission).execute()

    /** One caller owns this operation and its admission latch; no shared editor or retry state. */
    private inner class Submission(
        val access: CollaborationSpaceLocalAccess,
        val expected: CollaborationDraft?,
        val submission: CollaborationComposerSubmission,
    ) {
        val intent = collaborationSpaceComposerIntent(access, submission)
        val source = CollaborationSpaceInboxSource.GroupOriginal(submission.id)
        var denial: Failure? = null

        suspend fun execute(): AppResult<CollaborationComposerSubmission> {
            repeat(3) { attempt ->
                admitted().let { if (it is AppResult.Failed) return it }
                val command = messages.get(access, submission.id)
                admitted().let { if (it is AppResult.Failed) return it }
                if (command is AppResult.Failed) return command
                (command as AppResult.Success).value?.let {
                    return confirmed(it)
                }
                val retainedRead = outbox.get(access, submission.id)
                admitted().let { if (it is AppResult.Failed) return it }
                if (retainedRead is AppResult.Failed) return retainedRead
                val retained = (retainedRead as AppResult.Success).value
                if (retained != null && retained != submission) return changedDraft()
                if (retained != null) {
                    val existing = indexedOriginal()
                    if (existing is AppResult.Failed) return existing
                    if ((existing as AppResult.Success).value != null) return wake()
                }
                val local = inbox.conversation(access, intent.conversationId)
                admitted().let { if (it is AppResult.Failed) return it }
                if (local is AppResult.Failed) return local
                val captured =
                    captureCollaborationSpaceInboxConversation(
                        intent.conversationId,
                        (local as AppResult.Success).value,
                    )
                if (captured is AppResult.Failed) return captured
                val index = (captured as AppResult.Success).value
                val anchorsRead = inbox.activities(access, intent.conversationId, listOf(source))
                admitted().let { if (it is AppResult.Failed) return it }
                if (anchorsRead is AppResult.Failed) return anchorsRead
                val anchors =
                    (anchorsRead as AppResult.Success).value.let {
                        it.copy(values = it.values.toList())
                    }
                if (anchors.version != index.version) return@repeat
                val editing = drafts.get(access, intent.conversationId)
                admitted().let { if (it is AppResult.Failed) return it }
                if (editing is AppResult.Failed) return editing
                val editor = (editing as AppResult.Success).value
                if (retained == null && editor != expected) return changedDraft()
                if (retained != null && editor == null)
                    return collaborationRecoveryFailure(
                        "client.collaboration_invalid_response",
                        FailureKind.Unexpected,
                    )
                val canonical = metadata.getById(access, intent.conversationId)
                admitted().let { if (it is AppResult.Failed) return it }
                if (canonical is AppResult.Failed) return canonical
                val conversation = (canonical as AppResult.Success).value
                if (
                    conversation == null ||
                        conversation.id != intent.conversationId ||
                        !validCollaborationSpaceConversationMetadata(access, conversation)
                )
                    return collaborationRecoveryFailure(
                        "client.collaboration_conversation_unavailable",
                        FailureKind.Unavailable,
                    )
                val prepared =
                    collaborationSpaceInboxComposerPublication(
                        conversation,
                        index,
                        anchors,
                        submission,
                        if (retained == null) submission.nextDraft else requireNotNull(editor),
                        retained != null,
                    )
                if (prepared is AppResult.Failed) return prepared
                currentCoroutineContext().ensureActive()
                val handedOff =
                    outbox.handoff(
                        access,
                        submission,
                        expected,
                        (prepared as AppResult.Success).value,
                    )
                admitted().let { if (it is AppResult.Failed) return it }
                val interrupted = (handedOff as? AppResult.Failed)?.failure
                if (
                    interrupted != null &&
                        interrupted.kind !in setOf(FailureKind.Storage, FailureKind.Conflict)
                )
                    return AppResult.Failed(interrupted)
                when ((handedOff as? AppResult.Success)?.value) {
                    CollaborationComposerHandoffResult.CAPACITY_EXCEEDED ->
                        return collaborationRecoveryFailure(
                            "client.command_queue_full",
                            FailureKind.Storage,
                        )
                    CollaborationComposerHandoffResult.CHANGED ->
                        if (attempt == 2) return readback(null)
                    else -> return readback(interrupted)
                }
            }
            return changedDraft()
        }

        /** Exact source anchors only; this action never subscribes to or scans the inbox. */
        private suspend fun indexedOriginal(): AppResult<CollaborationSpaceInboxActivity?> {
            val read = inbox.activities(access, intent.conversationId, listOf(source))
            admitted().let { if (it is AppResult.Failed) return it }
            if (read is AppResult.Failed) return read
            return captureCollaborationSpaceInboxComposerAnchor(
                submission.id,
                (read as AppResult.Success).value,
            )
        }

        private suspend fun readback(
            interrupted: Failure?
        ): AppResult<CollaborationComposerSubmission> {
            // Concurrent recovery may move the original from the handoff into the command ledger.
            val retained = outbox.get(access, submission.id)
            admitted().let { if (it is AppResult.Failed) return it }
            if (retained is AppResult.Failed)
                return interrupted?.let { AppResult.Failed(it) } ?: retained
            (retained as AppResult.Success).value?.let {
                if (it != submission) return changedDraft()
                val indexed = indexedOriginal()
                if (indexed is AppResult.Failed)
                    return interrupted?.let { AppResult.Failed(it) } ?: indexed
                if ((indexed as AppResult.Success).value != null) return wake()
                return interrupted?.let { AppResult.Failed(it) } ?: changedDraft()
            }
            val command = messages.get(access, submission.id)
            admitted().let { if (it is AppResult.Failed) return it }
            if (command is AppResult.Failed) return command
            val saved =
                (command as AppResult.Success).value
                    ?: return interrupted?.let { AppResult.Failed(it) } ?: changedDraft()
            return confirmed(saved)
        }

        private suspend fun confirmed(
            record: CollaborationSpaceMessageRecord
        ): AppResult<CollaborationComposerSubmission> {
            if (!validCollaborationSpaceMessageRecord(access, record) || record.intent != intent)
                return changedDraft()
            return if (
                record.state is CollaborationMessageState.Sent ||
                    record.state == CollaborationMessageState.Cancelled
            )
                AppResult.Success(submission)
            else wake()
        }

        private suspend fun wake(): AppResult<CollaborationComposerSubmission> {
            // Storage committed the original and durable wake together. This is only an OS hint.
            withTimeoutOrNull(1_500) { schedules.request(CollaborationSpaceRecoveryWake.WORK_KEY) }
            val current = admitted()
            return if (current is AppResult.Failed) current else AppResult.Success(submission)
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
