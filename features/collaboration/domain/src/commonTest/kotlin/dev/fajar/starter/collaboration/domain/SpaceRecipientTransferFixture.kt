package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.TransferCollaborationSpaceRecipientSubmission
import dev.fajar.starter.common.result.*
import kotlin.test.*

internal class SpaceRecipientTransferFixture {
    val source = SpaceRecipientOutboxFixture()
    val access = source.access
    val submission = source.submission
    val directory = source.draft.base
    val canonical = SpaceMessageUseCaseFixture().canonical!!
    var byPeer: CollaborationSpaceConversationMetadata? = canonical
    val conversations = mutableMapOf(canonical.id to canonical)
    var inboxState = CollaborationSpaceInboxConversation(null, null, null, emptyList())
    var anchors = CollaborationSpaceInboxActivities(null, emptyList())
    var probeResult = CollaborationSpaceInboxSeedResult.RETAINED
    var insertionResult = CollaborationMessageInsertion.STORED
    var storeInserted = true
    var removeSource = true
    var removeReply = true
    var insertFailure: Failure? = null
    var removeFailure: Failure? = null
    var metadataFailure: Failure? = null
    var probeFailure: Failure? = null
    var insertedTarget: String? = null
    val calls = mutableListOf<String>()
    val hooks = mutableMapOf<String, suspend () -> Unit>()
    val proposals = mutableListOf<CollaborationSpaceMessageIntent>()
    val publications = mutableListOf<CollaborationSpaceInboxPublication>()

    init {
        source.retained[submission.id] = submission
        source.draft.editor = submission.nextDraft
        source.beforeRead = { hit("source") }
        source.beforeDestination = { hit("destination") }
        assertTrue(validCollaborationSpaceRecipientConversation(access, submission, canonical))
    }

    private suspend fun hit(boundary: String) {
        calls += boundary
        hooks[boundary]?.invoke()
    }

    val outbox =
        object : CollaborationSpaceRecipientOutboxRepository by source.outbox {
            override suspend fun remove(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceRecipientSubmission,
            ): AppResult<Boolean> {
                assertEquals(this@SpaceRecipientTransferFixture.access, access)
                assertEquals(submission, expected)
                hit("remove")
                if (removeSource && source.retained[expected.id] == expected)
                    source.retained.remove(expected.id)
                hit("removed")
                return removeFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(removeReply)
            }
        }

    val messages =
        object : CollaborationSpaceMessageRepository by source.messages {
            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                intent: CollaborationSpaceMessageIntent,
            ): AppResult<CollaborationMessageInsertion> {
                assertEquals(this@SpaceRecipientTransferFixture.access, access)
                proposals += intent
                hit("insert")
                if (storeInserted)
                    source.destination = record(insertedTarget ?: intent.conversationId)
                hit("inserted")
                return insertFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(insertionResult)
            }
        }

    val metadata =
        object : CollaborationSpaceConversationMetadataRepository {
            override suspend fun findByPeer(
                access: CollaborationSpaceLocalAccess,
                peerAccountId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> {
                assertEquals(this@SpaceRecipientTransferFixture.access, access)
                assertEquals(submission.original.peerAccountId, peerAccountId)
                hit("peer")
                return metadataFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(byPeer)
            }

            override suspend fun getById(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> {
                assertEquals(this@SpaceRecipientTransferFixture.access, access)
                assertEquals(source.destination!!.intent.conversationId, conversationId)
                hit("canonical")
                return metadataFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(conversations[conversationId])
            }

            override suspend fun retain(
                access: CollaborationSpaceLocalAccess,
                conversation: CollaborationSpaceConversation,
            ): AppResult<Boolean> = error("Transfer cannot provision or replace metadata")
        }

    val inbox =
        object : CollaborationSpaceInboxRepository by SpaceComposerUseCaseFixture().inbox {
            override suspend fun conversation(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                candidateLimit: Int,
            ): AppResult<CollaborationSpaceInboxConversation> {
                assertEquals(this@SpaceRecipientTransferFixture.access, access)
                assertEquals(source.destination!!.intent.conversationId, conversationId)
                assertEquals(2, candidateLimit)
                hit("index")
                return AppResult.Success(inboxState)
            }

            override suspend fun activities(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                sources: List<CollaborationSpaceInboxSource>,
            ): AppResult<CollaborationSpaceInboxActivities> {
                assertEquals(this@SpaceRecipientTransferFixture.access, access)
                assertEquals(source.destination!!.intent.conversationId, conversationId)
                assertEquals(
                    listOf(CollaborationSpaceInboxSource.GroupOriginal(submission.id)),
                    sources,
                )
                hit("anchor")
                return AppResult.Success(anchors)
            }

            override suspend fun seed(
                access: CollaborationSpaceLocalAccess,
                publication: CollaborationSpaceInboxPublication,
                repair: CollaborationSpaceInboxRepairPublication?,
            ): AppResult<CollaborationSpaceInboxSeedResult> {
                assertEquals(this@SpaceRecipientTransferFixture.access, access)
                assertNull(repair, "Transfer must never write an index or schedule repair")
                assertNull(publication.draft, "Independent canonical typing must stay owned")
                assertTrue(validCollaborationSpaceInboxPublication(publication))
                publications += publication
                hit("probe")
                return probeFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(probeResult)
            }
        }

    fun record(target: String = canonical.id) =
        CollaborationSpaceMessageRecord(
            collaborationSpaceRecipientMessageIntent(access, submission, target),
            0,
            CollaborationMessageState.Queued,
        )

    fun usecase() =
        TransferCollaborationSpaceRecipientSubmission(
            outbox,
            messages,
            metadata,
            inbox,
            source.draft.directory,
            source.draft.sessions,
            directory.native.server,
        )

    suspend fun invoke() = usecase()(access, submission)
}
