package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceComposerIntent
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.*

internal class SpaceComposerUseCaseFixture {
    val base = SpaceMessageUseCaseFixture()
    val access = base.access
    val original =
        CollaborationDraft(
            base.room.conversation.id,
            base.id,
            "  exact\ncontent  ",
            base.native.setup.now,
            replyToEventId = "\$reply",
        )
    val submission =
        CollaborationComposerSubmission(
            original,
            "e2000000-0000-4000-8000-000000000001",
            original.savedAt,
        )
    var editor: CollaborationDraft? = null
    val retained = mutableMapOf<String, CollaborationComposerSubmission>()
    val edits = mutableListOf<CollaborationDraft>()
    val handoffs = mutableListOf<CollaborationComposerSubmission>()
    var draftReads = 0
    var outboxReads = 0
    var beforeDraftRead: suspend () -> Unit = {}
    var beforeOutboxRead: suspend () -> Unit = {}
    var beforeEdit: suspend () -> Unit = {}
    var afterEdit: suspend () -> Unit = {}
    var beforeHandoff: suspend () -> Unit = {}
    var afterHandoff: suspend () -> Unit = {}
    var draftFailure: Failure? = null
    var editFailure: Failure? = null
    var handoffFailure: Failure? = null
    var handoffFailureBeforeCommit: Failure? = null
    var forcedHandoff: CollaborationComposerHandoffResult? = null
    var inboxState = CollaborationSpaceInboxConversation(null, null, null, emptyList())
    var inboxReads = 0
    var beforeInboxRead: suspend () -> Unit = {}
    var inboxFailure: Failure? = null
    val inbox =
        object : CollaborationSpaceInboxRepository {
            override fun observePage(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                limit: Int,
            ): Flow<AppResult<CollaborationSpaceInboxPage>> =
                error("Draft actions do not own an inbox subscription")

            override suspend fun conversation(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                candidateLimit: Int,
            ): AppResult<CollaborationSpaceInboxConversation> {
                assertEquals(
                    this@SpaceComposerUseCaseFixture.access.messageOwner(),
                    access.messageOwner(),
                )
                assertEquals(original.conversationId, conversationId)
                assertEquals(2, candidateLimit)
                inboxReads++
                beforeInboxRead()
                return inboxFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(inboxState)
            }

            override suspend fun activities(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                sources: List<CollaborationSpaceInboxSource>,
            ): AppResult<CollaborationSpaceInboxActivities> = error("No unrelated activity lookup")

            override suspend fun publish(
                access: CollaborationSpaceLocalAccess,
                publication: CollaborationSpaceInboxPublication,
            ): AppResult<Boolean> =
                error("Draft and index must be committed by the source repository")
        }

    val drafts =
        object : CollaborationSpaceDraftRepository {
            override fun observe(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): Flow<AppResult<CollaborationDraft?>> = error("Actions do not own a route collector")

            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationDraft?> {
                assertEquals(
                    this@SpaceComposerUseCaseFixture.access.messageOwner(),
                    access.messageOwner(),
                )
                assertEquals(original.conversationId, conversationId)
                draftReads++
                beforeDraftRead()
                return draftFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(editor)
            }

            override suspend fun replace(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationDraft?,
                replacement: CollaborationDraft,
                inbox: CollaborationSpaceInboxPublication,
            ): AppResult<Boolean> {
                beforeEdit()
                if (
                    (editor != expected && editor != replacement) ||
                        inbox.expected != inboxState.version
                )
                    return AppResult.Success(false)
                assertEquals(replacement, inbox.draft?.value)
                assertEquals(emptyList<CollaborationSpaceInboxActivity>(), inbox.activities)
                val version =
                    CollaborationSpaceInboxVersion(
                        inboxState.version?.generation ?: "d3000000-0000-4000-8000-000000000001",
                        (inboxState.version?.revision ?: -1) + 1,
                        inbox.lastOrdinal,
                    )
                inboxState =
                    CollaborationSpaceInboxConversation(
                        version,
                        inbox.entry,
                        inbox.draft,
                        inboxState.candidates,
                    )
                editor = replacement
                edits += replacement
                afterEdit()
                return editFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
            }
        }
    val outbox =
        object : CollaborationSpaceComposerOutboxRepository {
            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationComposerSubmission?> {
                assertEquals(
                    this@SpaceComposerUseCaseFixture.access.messageOwner(),
                    access.messageOwner(),
                )
                outboxReads++
                beforeOutboxRead()
                return AppResult.Success(retained[id])
            }

            override suspend fun handoff(
                access: CollaborationSpaceLocalAccess,
                submission: CollaborationComposerSubmission,
                expected: CollaborationDraft?,
            ): AppResult<CollaborationComposerHandoffResult> {
                beforeHandoff()
                handoffFailureBeforeCommit?.let {
                    return AppResult.Failed(it)
                }
                forcedHandoff?.let {
                    return AppResult.Success(it)
                }
                if (retained.containsKey(submission.id))
                    return AppResult.Success(
                        if (retained[submission.id] == submission)
                            CollaborationComposerHandoffResult.RETAINED
                        else CollaborationComposerHandoffResult.CHANGED
                    )
                if (editor != expected)
                    return AppResult.Success(CollaborationComposerHandoffResult.CHANGED)
                editor = submission.nextDraft
                retained[submission.id] = submission
                handoffs += submission
                afterHandoff()
                return handoffFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(CollaborationComposerHandoffResult.STORED)
            }

            override fun observe(
                access: CollaborationSpaceLocalAccess
            ): Flow<AppResult<List<CollaborationComposerOutboxEntry>>> = error("No collector")

            override suspend fun hold(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationComposerSubmission,
            ): AppResult<Boolean> = error("No recovery")

            override suspend fun pending(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationComposerSubmissionPage> = error("No recovery scan")

            override suspend fun remove(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationComposerSubmission,
            ): AppResult<Boolean> = error("No retirement")
        }

    fun edit() =
        EditCollaborationSpaceDraft(
            drafts,
            inbox,
            base.metadata,
            base.base.directory,
            base.sessions,
            base.native.server,
        )

    fun submit() =
        SubmitCollaborationSpaceComposer(
            outbox,
            base.messages,
            base.metadata,
            base.base.directory,
            base.sessions,
            base.native.server,
            base.scheduler,
        )

    fun record(state: CollaborationMessageState = CollaborationMessageState.Queued) =
        CollaborationSpaceMessageRecord(
            collaborationSpaceComposerIntent(access, submission),
            0,
            state,
        )
}
