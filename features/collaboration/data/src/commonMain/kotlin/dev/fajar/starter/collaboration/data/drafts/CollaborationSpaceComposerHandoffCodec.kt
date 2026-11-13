@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.drafts

import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.uuid.Uuid

/** Authenticated source/index mapping for one key lease. No I/O, retries or admission policy. */
internal class CollaborationSpaceComposerHandoffCodec(
    access: CollaborationSpaceLocalAccess,
    cipher: ContentCipher,
    contentKey: ByteArray,
    indexKey: ByteArray,
) {
    private val coordinates = CollaborationComposerCoordinates.Space(access)
    private val drafts = CollaborationDraftCodec(coordinates, cipher, contentKey)
    private val originals = CollaborationComposerSubmissionCodec(coordinates, cipher, contentKey)
    private val inbox = CollaborationSpaceInboxCodec(access, cipher, contentKey, indexKey)

    /** The original format keeps active and held submissions in two indexed subsets. */
    val outstandingQueries =
        listOf(
            ReplicaQuery(COMPOSER_OUTBOX_TAG, limit = 129),
            ReplicaQuery(COMPOSER_HELD_TAG, limit = 129),
        )

    data class Source(val checkpoint: ReplicaCheckpoint?, val retained: Boolean)

    data class Index(val checkpoint: ReplicaCheckpoint?, val mutation: ReplicaMutation?)

    suspend fun source(
        captured: ReplicaPage,
        submission: CollaborationComposerSubmission,
        expected: CollaborationDraft?,
        proposal: CollaborationSpaceInboxPublication,
    ): Source? {
        val checkpoint = captured.checkpoint
        require(
            captured.documents.size <= 2 && (checkpoint != null || captured.documents.isEmpty())
        )
        if (checkpoint != null) drafts.checkpoint(checkpoint)
        val editor =
            captured.documents
                .singleOrNull { it.id == submission.original.conversationId }
                ?.let { drafts.decode(requireNotNull(checkpoint), it) }
        val retained =
            captured.documents
                .singleOrNull { it.id == submission.id }
                ?.let { originals.decode(requireNotNull(checkpoint), it) }
        if (retained != null && retained != submission) return null
        if (retained == null && editor != expected) return null
        if (proposal.draft?.value != (if (retained == null) submission.nextDraft else editor))
            return null
        return Source(checkpoint, retained != null)
    }

    suspend fun indexDocumentIds(
        submission: CollaborationComposerSubmission,
        proposal: CollaborationSpaceInboxPublication,
    ): List<String> {
        val conversation = submission.original.conversationId
        return buildList {
                add(inbox.id("entry", conversation))
                add(inbox.id("draft", conversation))
                add(
                    inbox.id(
                        "activity",
                        conversation,
                        CollaborationSpaceInboxSource.GroupOriginal(submission.id),
                    )
                )
                proposal.entry.newest?.let { add(inbox.id("activity", conversation, it.source)) }
            }
            .distinct()
    }

    suspend fun index(
        held: ReplicaPage,
        submission: CollaborationComposerSubmission,
        proposal: CollaborationSpaceInboxPublication,
        retained: Boolean,
    ): Index? {
        val raw = held.checkpoint
        if (raw?.let { inbox.version(it) } != proposal.expected) return null
        val conversation = submission.original.conversationId
        val source = CollaborationSpaceInboxSource.GroupOriginal(submission.id)
        val activity = proposal.activities.single()
        require(activity.source == source && proposal.entry.conversationId == conversation)
        val entryId = inbox.id("entry", conversation)
        val activityId = inbox.id("activity", conversation, source)
        val previousEntry =
            held.documents
                .singleOrNull { it.id == entryId }
                ?.let { inbox.decode(requireNotNull(raw), it).entry?.domain(conversation) }
        val previousActivity =
            held.documents
                .singleOrNull { it.id == activityId }
                ?.let { inbox.decode(requireNotNull(raw), it).activity?.domain() }
        if (previousActivity != null) {
            if (!retained) return null
            require(activity == previousActivity)
        } else {
            require(
                activity.preview ==
                    CollaborationSpaceInboxPreview(
                        CollaborationSpaceInboxContent.TEXT,
                        submission.original.text,
                        submission.submittedAt.toEpochMilliseconds(),
                        CollaborationMessageDelivery.PENDING,
                    )
            )
        }
        val newest =
            listOfNotNull(previousEntry?.newest, activity.takeIf { it.preview != null })
                .maxWithOrNull { one, two ->
                    compareCollaborationSpaceInboxPositions(one.position, two.position)
                }
        require(proposal.entry.newest == newest)
        return Index(raw, inbox.mutation(raw, held.documents, proposal))
    }

    /** Preserve original documents on repair; even an unchanged index carries its own CAS. */
    suspend fun mutations(
        source: Source,
        index: Index,
        submission: CollaborationComposerSubmission,
    ): List<ReplicaMutation> {
        val checkpoint = source.checkpoint
        val revision = checkpoint?.revision?.plus(1) ?: 0L
        require(revision in 0 until COLLABORATION_SPACE_INBOX_MAX_ORDER)
        val generation = checkpoint?.handle?.generation ?: Uuid.random().toString()
        val documents =
            if (!source.retained)
                listOf(
                    drafts.encode(generation, revision, submission.nextDraft),
                    originals.encode(generation, revision, submission),
                )
            else emptyList()
        val payload = drafts.checkpoint(generation, revision)
        val original =
            if (checkpoint == null)
                ReplicaMutation.Replace(
                    coordinates.partition,
                    null,
                    ReplicaSnapshot(generation, payload, documents),
                )
            else
                ReplicaMutation.Commit(
                    checkpoint.handle,
                    checkpoint.revision,
                    payload,
                    documents,
                    true,
                )
        return listOf(
            original,
            index.mutation ?: inbox.guardCheckpoint(requireNotNull(index.checkpoint)),
        )
    }
}
