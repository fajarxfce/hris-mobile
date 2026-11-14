@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.drafts

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.uuid.Uuid

/** Maps one frozen source and replacement to authenticated storage; no I/O or admission policy. */
internal class CollaborationSpaceRecipientHandoffCodec(
    access: CollaborationSpaceLocalAccess,
    cipher: ContentCipher,
    key: ByteArray,
) {
    private val drafts = CollaborationSpaceRecipientDraftCodec(access, cipher, key)
    private val originals = CollaborationSpaceRecipientSubmissionCodec(access, cipher, key)

    data class Source(
        val editor: CollaborationSpaceRecipientDraft?,
        val retained: CollaborationSpaceRecipientSubmission?,
    )

    suspend fun source(
        checkpoint: ReplicaCheckpoint?,
        documents: List<ReplicaDocument>,
        submission: CollaborationSpaceRecipientSubmission,
    ): Source {
        val id = recipientSubmissionId(submission.id)
        require(documents.size <= 2 && documents.map { it.id }.distinct().size == documents.size)
        require(checkpoint != null || documents.isEmpty())
        if (checkpoint != null) drafts.checkpoint(checkpoint)
        require(documents.all { it.id in setOf(submission.original.peerAccountId, id) })
        return Source(
            documents
                .singleOrNull { it.id == submission.original.peerAccountId }
                ?.let { drafts.decode(requireNotNull(checkpoint), it) },
            documents
                .singleOrNull { it.id == id }
                ?.let { originals.decode(requireNotNull(checkpoint), it) },
        )
    }

    suspend fun replacement(
        checkpoint: ReplicaCheckpoint?,
        submission: CollaborationSpaceRecipientSubmission,
    ): ReplicaSnapshot {
        val generation = checkpoint?.handle?.generation ?: Uuid.random().toString()
        val revision = checkpoint?.revision?.plus(1) ?: 0L
        require(revision in 0 until 9_007_199_254_740_991L)
        return ReplicaSnapshot(
            generation,
            drafts.checkpoint(generation, revision),
            listOf(
                drafts.encode(generation, revision, submission.nextDraft),
                originals.encode(generation, revision, submission),
            ),
        )
    }
}
