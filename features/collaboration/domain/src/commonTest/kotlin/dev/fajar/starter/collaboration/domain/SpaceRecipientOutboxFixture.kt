package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.SubmitCollaborationSpaceRecipientComposer
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow

internal class SpaceRecipientOutboxFixture {
    val draft = SpaceRecipientDraftFixture()
    val access = draft.access
    val submission =
        CollaborationSpaceRecipientSubmission(
            draft.original,
            draft.base.generation,
            draft.original.savedAt,
        )
    val retained = mutableMapOf<String, CollaborationSpaceRecipientSubmission>()
    var reads = 0
    var handoffs = 0
    var persist = true
    var readFailure: Failure? = null
    var writeFailure: Failure? = null
    var result = CollaborationComposerHandoffResult.STORED
    var beforeRead: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    var destination: CollaborationSpaceMessageRecord? = null
    var destinationReads = 0
    var destinationFailure: Failure? = null
    var beforeDestination: suspend () -> Unit = {}
    val messages =
        object : CollaborationSpaceMessageRepository {
            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageRecord?> {
                assertEquals(this@SpaceRecipientOutboxFixture.access, access)
                assertEquals(submission.id, id)
                destinationReads++
                beforeDestination()
                return destinationFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(destination)
            }

            override fun observe(
                access: CollaborationSpaceLocalAccess,
                conversationId: String?,
            ): Flow<AppResult<List<CollaborationSpaceMessageRecord>>> = error("No inventory scan")

            override fun observeRecord(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): Flow<AppResult<CollaborationSpaceMessageRecord?>> = error("No retained reader")

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                intent: CollaborationSpaceMessageIntent,
            ): AppResult<CollaborationMessageInsertion> = error("Submission cannot transfer")

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceMessageRecord,
                state: CollaborationMessageState,
            ): AppResult<Boolean> = error("Submission cannot deliver")
        }
    val outbox =
        object : CollaborationSpaceRecipientOutboxRepository {
            override fun observeVersion(
                access: CollaborationSpaceLocalAccess
            ): Flow<AppResult<CollaborationSpaceRecipientOutboxVersion?>> =
                error("Submission does not own source observation")

            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceRecipientSubmission?> {
                reads++
                beforeRead()
                return readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(retained[id])
            }

            override suspend fun handoff(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceRecipientDraft?,
                submission: CollaborationSpaceRecipientSubmission,
            ): AppResult<CollaborationComposerHandoffResult> {
                assertEquals(this@SpaceRecipientOutboxFixture.access, access)
                handoffs++
                if (
                    persist &&
                        result == CollaborationComposerHandoffResult.STORED &&
                        draft.editor == expected
                ) {
                    retained[submission.id] = submission
                    draft.editor = submission.nextDraft
                }
                afterWrite()
                return writeFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(result)
            }

            override suspend fun pending(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationSpaceRecipientSubmissionPage> =
                error("Submission must not scan recovery pages")

            override suspend fun remove(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceRecipientSubmission,
            ): AppResult<Boolean> = error("Submission cannot retire its source")
        }

    fun submit() =
        SubmitCollaborationSpaceRecipientComposer(
            outbox,
            draft.drafts,
            messages,
            draft.directory,
            draft.sessions,
            draft.base.native.server,
        )
}
