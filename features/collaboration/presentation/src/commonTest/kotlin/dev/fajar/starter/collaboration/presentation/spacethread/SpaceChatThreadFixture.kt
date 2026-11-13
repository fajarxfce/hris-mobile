package dev.fajar.starter.collaboration.presentation.spacethread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceComposerIntent
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceConversationMetadata
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.thread.ChatThreadFixture
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.flow.*

internal class SpaceChatThreadFixture {
    val original = ChatThreadFixture()
    val base = original.base
    val access =
        CollaborationSpaceLocalAccess(
            base.initial.id,
            base.server.audience,
            base.initial.user.id,
            chatId(800),
        )
    val conversationId = chatId(801)
    val metadata =
        CollaborationSpaceConversationMetadata(
            access.spaceId,
            conversationId,
            access.accountId,
            chatId(802),
            access.accountId,
            base.now,
            0,
        )

    init {
        assertTrue(validCollaborationSpaceConversationMetadata(access, metadata))
    }

    val gate =
        MutableStateFlow<AppResult<CollaborationSpaceDirectoryAdmission>>(
            AppResult.Success(CollaborationSpaceDirectoryAdmission(null, null))
        )
    val draft = MutableStateFlow<CollaborationDraft?>(null)
    val entries = MutableStateFlow<List<CollaborationComposerOutboxEntry>>(emptyList())
    val messages = MutableStateFlow<List<CollaborationSpaceMessageRecord>>(emptyList())
    val archived = MutableStateFlow<List<CollaborationSpaceMessageRecord>>(emptyList())
    var recordReaders = 0
    var recordFailure: Failure? = null
    var beforeRecord: suspend () -> Unit = {}
    val storage = Failure(FailureKind.Storage, "Fictional local interruption")
    val denial = Failure(FailureKind.AccessDenied, "Fictional Group denial")
    var beforeDraftObservation: suspend () -> Unit = {}
    var beforeMetadata: suspend () -> Unit = {}
    var beforeHandoff: suspend () -> Unit = {}
    var beforeEdit: suspend () -> Unit = {}
    var afterHandoff: suspend () -> Unit = {}
    var editFailure: Failure? = null
    var readFailure: Failure? = null
    var draftReaders = 0
    var outboxReaders = 0
    var draftStarts = 0
    var schedules = 0
    val edits = mutableListOf<CollaborationDraft>()
    val handoffs = mutableListOf<CollaborationComposerSubmission>()
    val directory =
        object : CollaborationSpaceDirectoryRepository {
            override fun observeAdmission(access: CollaborationSpaceLocalAccess) = gate

            override suspend fun admission(access: CollaborationSpaceLocalAccess) = gate.value

            override fun observeContact(
                access: CollaborationSpaceLocalAccess,
                accountId: String,
            ): Flow<AppResult<CollaborationSpaceContact?>> = flowOf(AppResult.Success(null))

            override fun observe(
                access: CollaborationSpaceLocalAccess
            ): Flow<AppResult<CollaborationSpaceDirectorySnapshot>> =
                error("No full directory read")

            override suspend fun checkpoint(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceDirectoryCheckpoint?> = error("No directory acquisition")

            override suspend fun begin(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceDirectoryCheckpoint?,
                generation: String,
                startedAt: Instant,
            ): AppResult<Boolean> = error("No directory acquisition")

            override suspend fun append(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceDirectoryCheckpoint,
                page: CollaborationSpaceContactPage,
                acquiredAt: Instant,
            ): AppResult<Boolean> = error("No directory acquisition")

            override suspend fun restrict(
                access: CollaborationSpaceLocalAccess,
                failure: Failure,
            ): AppResult<Boolean> = error("No admission mutation")

            override suspend fun admit(
                access: CollaborationSpaceLocalAccess,
                expectedRevision: String?,
            ): AppResult<Boolean> = error("No admission mutation")
        }
    val drafts =
        object : CollaborationSpaceDraftRepository {
            override fun observe(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): Flow<AppResult<CollaborationDraft?>> = flow {
                assertEquals(this@SpaceChatThreadFixture.access, access)
                assertEquals(this@SpaceChatThreadFixture.conversationId, conversationId)
                draftReaders++
                draftStarts++
                try {
                    beforeDraftObservation()
                    emitAll(
                        draft.map {
                            readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(it)
                        }
                    )
                } finally {
                    draftReaders--
                }
            }

            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationDraft?> = AppResult.Success(draft.value)

            override suspend fun replace(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationDraft?,
                replacement: CollaborationDraft,
            ): AppResult<Boolean> {
                assertEquals(this@SpaceChatThreadFixture.access, access)
                beforeEdit()
                editFailure?.let {
                    return AppResult.Failed(it)
                }
                if (draft.value != expected) return AppResult.Success(false)
                edits += replacement
                draft.value = replacement
                return AppResult.Success(true)
            }
        }
    val outbox =
        object : CollaborationSpaceComposerOutboxRepository {
            override fun observe(
                access: CollaborationSpaceLocalAccess
            ): Flow<AppResult<List<CollaborationComposerOutboxEntry>>> = flow {
                assertEquals(this@SpaceChatThreadFixture.access, access)
                outboxReaders++
                try {
                    emitAll(entries.map { AppResult.Success(it) })
                } finally {
                    outboxReaders--
                }
            }

            override suspend fun get(access: CollaborationSpaceLocalAccess, id: String) =
                AppResult.Success(entries.value.firstOrNull { it.submission.id == id }?.submission)

            override suspend fun handoff(
                access: CollaborationSpaceLocalAccess,
                submission: CollaborationComposerSubmission,
                expected: CollaborationDraft?,
            ): AppResult<CollaborationComposerHandoffResult> {
                assertEquals(this@SpaceChatThreadFixture.access, access)
                beforeHandoff()
                entries.value
                    .firstOrNull { it.submission.id == submission.id }
                    ?.let {
                        assertEquals(it.submission, submission)
                        return AppResult.Success(CollaborationComposerHandoffResult.RETAINED)
                    }
                if (draft.value != expected)
                    return AppResult.Success(CollaborationComposerHandoffResult.CHANGED)
                handoffs += submission
                entries.value += CollaborationComposerOutboxEntry(submission, false)
                draft.value = submission.nextDraft
                afterHandoff()
                return AppResult.Success(CollaborationComposerHandoffResult.STORED)
            }

            override suspend fun hold(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationComposerSubmission,
            ): AppResult<Boolean> = error("No command cancellation")

            override suspend fun pending(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationComposerSubmissionPage> = error("No worker scan")

            override suspend fun remove(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationComposerSubmission,
            ): AppResult<Boolean> = error("No command retirement")
        }
    val schedule =
        object : SyncScheduleRepository {
            override suspend fun request(key: String): AppResult<Unit> {
                schedules++
                return AppResult.Success(Unit)
            }
        }
    val content = SpaceChatThreadContentFixture(this)

    fun model() =
        SpaceChatThreadViewModel(
            access,
            conversationId,
            ObserveCollaborationSpaceThread(
                content.bindings,
                content.metadata,
                content.history,
                original.history,
                content.commands,
                original.messages,
                content.registrations,
                original.identities,
                directory,
                base.sessions,
                base.server,
            ),
            ObserveCollaborationSpaceComposer(
                drafts,
                outbox,
                directory,
                base.sessions,
                base.server,
            ),
            ObserveCollaborationSpaceComposerProgress(
                content.commands,
                directory,
                base.sessions,
                base.server,
            ),
            EditCollaborationSpaceDraft(
                drafts,
                content.metadata,
                directory,
                base.sessions,
                base.server,
            ),
            SubmitCollaborationSpaceComposer(
                outbox,
                content.commands,
                content.metadata,
                directory,
                base.sessions,
                base.server,
                schedule,
            ),
            ReadCollaborationSpaceThreadContext(
                content.metadata,
                directory,
                base.sessions,
                base.server,
            ),
        )

    fun submission(index: Int = 1) =
        CollaborationComposerSubmission(
            CollaborationDraft(
                conversationId,
                chatId(3000 + index),
                "Retained original $index",
                base.now,
            ),
            chatId(4000 + index),
            base.now,
        )

    fun sent(original: CollaborationComposerSubmission) =
        CollaborationSpaceMessageRecord(
            collaborationSpaceComposerIntent(access, original),
            3,
            CollaborationMessageState.Sent(
                CollaborationMessageDevice(
                    chatId(5001),
                    chatId(5002),
                    chatId(5003),
                    "!room:example.test",
                ),
                "original-transaction",
                "${'$'}event",
                CollaborationMessageOrigin("@fictional:example.test", "a".repeat(64)),
            ),
        )

    fun deny() {
        gate.value = AppResult.Success(CollaborationSpaceDirectoryAdmission(chatId(899), denial))
    }
}
