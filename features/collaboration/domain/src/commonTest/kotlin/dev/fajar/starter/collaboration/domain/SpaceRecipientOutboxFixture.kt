package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.SubmitCollaborationSpaceRecipientComposer
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals

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
    val outbox =
        object : CollaborationSpaceRecipientOutboxRepository {
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
        }

    fun submit() =
        SubmitCollaborationSpaceRecipientComposer(
            outbox,
            draft.drafts,
            draft.directory,
            draft.sessions,
            draft.base.native.server,
        )
}
