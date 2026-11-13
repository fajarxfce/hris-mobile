package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull

/** One finite local pass. Neither native keys nor an HR company are prerequisites. */
class RecoverCollaborationSpaceComposer(
    private val roots: CollaborationSpaceRootRepository,
    private val outbox: CollaborationSpaceComposerOutboxRepository,
    private val cursors: CollaborationSpaceComposerRecoveryCursorRepository,
    private val messages: CollaborationSpaceMessageRepository,
    private val metadata: CollaborationSpaceConversationMetadataRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    /** Separate retained editing work from commands that still require a native device. */
    suspend operator fun invoke(): AppResult<CollaborationSpaceComposerRecovery> =
        withTimeoutOrNull(5_000) { discover() }
            ?: collaborationRecoveryFailure(
                "client.collaboration_composer_recovery_timeout",
                FailureKind.Timeout,
            )

    private suspend fun discover(): AppResult<CollaborationSpaceComposerRecovery> {
        currentCoroutineContext().ensureActive()
        val found = sessions.current()
        currentCoroutineContext().ensureActive()
        if (found is AppResult.Failed) return found
        val session =
            (found as AppResult.Success).value
                ?: return AppResult.Success(CollaborationSpaceComposerRecovery(false, false))
        val account = CollaborationSpaceAccountAccess(session.id, server.audience, session.user.id)
        val owned = localCollaborationSpaceAccountAccess(account, session, server.audience)
        if (owned is AppResult.Failed) return owned
        val loaded = roots.current(account)
        currentCoroutineContext().ensureActive()
        val latest = sessions.current()
        currentCoroutineContext().ensureActive()
        if (latest is AppResult.Failed) return latest
        val current =
            localCollaborationSpaceAccountAccess(
                account,
                (latest as AppResult.Success).value,
                server.audience,
            )
        if (current is AppResult.Failed) return current
        if (loaded is AppResult.Failed) return loaded
        val root =
            (loaded as AppResult.Success).value
                ?: return AppResult.Success(CollaborationSpaceComposerRecovery(false, false))
        if (!validCollaborationMessageId(root.spaceId)) return changedDraft()
        return Recovery(root.localDirectoryAccess(account)).run()
    }

    private inner class Recovery(val access: CollaborationSpaceLocalAccess) {
        var denial: Failure? = null

        suspend fun run(): AppResult<CollaborationSpaceComposerRecovery> {
            admitted().let { if (it is AppResult.Failed) return it }
            val saved = cursors.current(access)
            admitted().let { if (it is AppResult.Failed) return it }
            if (saved is AppResult.Failed) return saved
            var cursor = (saved as AppResult.Success).value
            if (
                cursor != null &&
                    (!validCollaborationMessageId(cursor.generation) ||
                        !validCollaborationMessageId(cursor.afterSubmissionId))
            )
                return changedDraft()
            val found = page(cursor?.afterSubmissionId)
            if (found is AppResult.Failed) return found
            var failure: Failure? = null
            for (original in (found as AppResult.Success).value.submissions) {
                val advanced = cursors.advance(access, cursor, original.id)
                admitted().let { if (it is AppResult.Failed) return it }
                if (advanced is AppResult.Failed) return advanced
                cursor = (advanced as AppResult.Success).value ?: return changedDraft()
                if (
                    !validCollaborationMessageId(cursor.generation) ||
                        cursor.afterSubmissionId != original.id
                )
                    return changedDraft()
                // A failed room cannot pin subsequent attempts to the beginning of the outbox.
                val recovered = materialize(original)
                admitted().let { if (it is AppResult.Failed) return it }
                if (recovered is AppResult.Failed) failure = failure ?: recovered.failure
            }
            val remaining = page(cursor?.afterSubmissionId)
            if (remaining is AppResult.Failed) return remaining
            if (failure != null) return AppResult.Failed(failure)
            val read = messages.observe(access, null).firstOrNull()
            val captured =
                (read as? AppResult.Success)?.value?.let {
                    collaborationSpaceMessageInventory(access, it)
                }
            admitted().let { if (it is AppResult.Failed) return it }
            if (read is AppResult.Failed) return read
            if (captured == null)
                return collaborationRecoveryFailure(
                    "client.collaboration_space_pending_inventory_changed",
                    FailureKind.Conflict,
                )
            return AppResult.Success(
                CollaborationSpaceComposerRecovery(
                    pendingSubmissions =
                        (remaining as AppResult.Success).value.submissions.isNotEmpty(),
                    pendingMessages =
                        captured.any {
                            it.state !is CollaborationMessageState.Sent &&
                                it.state != CollaborationMessageState.Cancelled
                        },
                )
            )
        }

        private suspend fun page(after: String?): AppResult<CollaborationComposerSubmissionPage> {
            var read = outbox.pending(access, after)
            admitted().let { if (it is AppResult.Failed) return it }
            if (read is AppResult.Failed) return read
            var captured =
                captureCollaborationSpaceComposerPage(
                    access,
                    (read as AppResult.Success).value,
                    after,
                ) ?: return changedDraft()
            if (captured.submissions.isEmpty() && after != null) {
                read = outbox.pending(access)
                admitted().let { if (it is AppResult.Failed) return it }
                if (read is AppResult.Failed) return read
                captured =
                    captureCollaborationSpaceComposerPage(access, (read as AppResult.Success).value)
                        ?: return changedDraft()
            }
            return AppResult.Success(captured)
        }

        private suspend fun materialize(
            original: CollaborationComposerSubmission
        ): AppResult<Unit> {
            val retained = outbox.get(access, original.id)
            admitted().let { if (it is AppResult.Failed) return it }
            if (retained is AppResult.Failed) return retained
            // Disappearance grants no new insertion. Another finite pass may have retired it.
            val value = (retained as AppResult.Success).value ?: return AppResult.Success(Unit)
            if (value != original) return changedDraft()
            val intent = collaborationSpaceComposerIntent(access, original)
            val existing = messages.get(access, original.id)
            admitted().let { if (it is AppResult.Failed) return it }
            if (existing is AppResult.Failed) return existing
            (existing as AppResult.Success).value?.let {
                return if (validCollaborationSpaceMessageRecord(access, it) && it.intent == intent)
                    retire(original)
                else hold(original)
            }
            val loaded = metadata.getById(access, intent.conversationId)
            admitted().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            val conversation = (loaded as AppResult.Success).value
            if (
                conversation == null ||
                    conversation.id != intent.conversationId ||
                    !validCollaborationSpaceConversationMetadata(access, conversation)
            ) {
                val raced = messages.get(access, original.id)
                admitted().let { if (it is AppResult.Failed) return it }
                if (raced is AppResult.Failed) return raced
                val record = (raced as AppResult.Success).value
                return if (
                    record != null &&
                        validCollaborationSpaceMessageRecord(access, record) &&
                        record.intent == intent
                )
                    retire(original)
                else hold(original)
            }
            return insertAndConfirm(original)
        }

        private suspend fun insertAndConfirm(
            original: CollaborationComposerSubmission
        ): AppResult<Unit> {
            val intent = collaborationSpaceComposerIntent(access, original)
            val inserted = messages.insert(access, intent)
            admitted().let { if (it is AppResult.Failed) return it }
            val interrupted = (inserted as? AppResult.Failed)?.failure
            if (
                interrupted != null &&
                    interrupted.kind !in setOf(FailureKind.Storage, FailureKind.Conflict)
            )
                return AppResult.Failed(interrupted)
            when ((inserted as? AppResult.Success)?.value) {
                CollaborationMessageInsertion.CAPACITY_EXCEEDED ->
                    return collaborationRecoveryFailure(
                        "client.command_queue_full",
                        FailureKind.Storage,
                    )
                CollaborationMessageInsertion.SCOPE_CHANGED ->
                    return collaborationRecoveryFailure(
                        "client.session_changed",
                        FailureKind.Unauthorized,
                    )
                CollaborationMessageInsertion.STORED,
                null -> Unit
            }
            val readback = messages.get(access, original.id)
            admitted().let { if (it is AppResult.Failed) return it }
            if (readback is AppResult.Failed) return readback
            val record =
                (readback as AppResult.Success).value
                    ?: return interrupted?.let { AppResult.Failed(it) } ?: changedDraft()
            return if (
                validCollaborationSpaceMessageRecord(access, record) && record.intent == intent
            )
                retire(original)
            else hold(original)
        }

        private suspend fun retire(original: CollaborationComposerSubmission): AppResult<Unit> {
            val removed = outbox.remove(access, original)
            admitted().let { if (it is AppResult.Failed) return it }
            if (removed is AppResult.Failed) return removed
            if ((removed as AppResult.Success).value) return AppResult.Success(Unit)
            val remaining = outbox.get(access, original.id)
            admitted().let { if (it is AppResult.Failed) return it }
            if (remaining is AppResult.Failed) return remaining
            return if ((remaining as AppResult.Success).value == null) AppResult.Success(Unit)
            else changedDraft()
        }

        private suspend fun hold(original: CollaborationComposerSubmission): AppResult<Unit> {
            val held = outbox.hold(access, original)
            admitted().let { if (it is AppResult.Failed) return it }
            if (held is AppResult.Failed) return held
            return if ((held as AppResult.Success).value) AppResult.Success(Unit)
            else changedDraft()
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
            if (!validCollaborationSpaceDirectoryAdmission(gate)) return changedDraft()
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
    }
}
