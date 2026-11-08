package dev.fajar.starter.collaboration.data.drafts

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationComposerSubmission
import dev.fajar.starter.collaboration.domain.policies.validCollaborationMessageId
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val COMPOSER_OUTBOX_TAG = "composer-outbox-v1"

/** Separate authenticated payload type; the shared partition keeps handoff atomic. */
internal class CollaborationComposerSubmissionCodec(
    private val access: CollaborationLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun encode(
        generation: String,
        revision: Long,
        submission: CollaborationComposerSubmission,
    ): ReplicaDocument {
        require(validCollaborationComposerSubmission(submission))
        val original = submission.original
        val dto =
            ComposerSubmissionDto(
                conversationId = original.conversationId,
                id = submission.id,
                text = original.text,
                draftSavedAt = original.savedAt.toString(),
                replyToEventId = original.replyToEventId,
                nextRevision = submission.nextRevision,
                submittedAt = submission.submittedAt.toString(),
            )
        val bytes = Json.encodeToString(dto).encodeToByteArray(throwOnInvalidSequence = true)
        try {
            require(bytes.size <= 96_000)
            val payload =
                Base64.encode(cipher.encrypt(key, aad(generation, submission.id, revision), bytes))
            require(payload.length <= 131_072)
            return ReplicaDocument(
                submission.id,
                revision,
                submission.id,
                payload,
                setOf(COMPOSER_OUTBOX_TAG),
            )
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun decode(
        checkpoint: ReplicaCheckpoint,
        document: ReplicaDocument,
    ): CollaborationComposerSubmission {
        require(validCollaborationMessageId(document.id) && document.sortKey == document.id)
        require(document.version in 0..checkpoint.revision)
        require(document.tags == setOf(COMPOSER_OUTBOX_TAG))
        val payload = requireNotNull(document.payload)
        require(payload.length in 1..131_072)
        val bytes =
            cipher.decrypt(
                key,
                aad(checkpoint.handle.generation, document.id, document.version),
                Base64.decode(payload),
            )
        val dto =
            try {
                require(bytes.size <= 96_000)
                Json.decodeFromString<ComposerSubmissionDto>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
            } finally {
                bytes.fill(0)
            }
        require(dto.schema == 1 && dto.id == document.id)
        return CollaborationComposerSubmission(
                CollaborationDraft(
                    dto.conversationId,
                    dto.id,
                    dto.text,
                    Instant.parse(dto.draftSavedAt),
                    replyToEventId = dto.replyToEventId,
                ),
                dto.nextRevision,
                Instant.parse(dto.submittedAt),
            )
            .also { require(validCollaborationComposerSubmission(it)) }
    }

    private fun aad(generation: String, id: String, revision: Long) =
        Json.encodeToString(
                listOf(
                    "hris-composer-outbox-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    draftPartition(access),
                    generation,
                    id,
                    revision.toString(),
                )
            )
            .encodeToByteArray()
}

@Serializable
private data class ComposerSubmissionDto(
    val schema: Int = 1,
    val conversationId: String,
    val id: String,
    val text: String,
    val draftSavedAt: String,
    val replyToEventId: String?,
    val nextRevision: String,
    val submittedAt: String,
) {
    override fun toString() = "ComposerSubmissionDto([redacted])"
}
