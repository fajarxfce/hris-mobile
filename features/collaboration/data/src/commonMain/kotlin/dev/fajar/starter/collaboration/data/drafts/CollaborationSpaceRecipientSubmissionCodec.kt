package dev.fajar.starter.collaboration.data.drafts

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val RECIPIENT_OUTBOX_TAG = "recipient-outbox-v1"

internal fun recipientSubmissionId(id: String): String {
    require(validCollaborationMessageId(id))
    return "submission:$id"
}

/** Immutable originals share the editor transaction but have a distinct authenticated frame. */
internal class CollaborationSpaceRecipientSubmissionCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun encode(
        generation: String,
        revision: Long,
        value: CollaborationSpaceRecipientSubmission,
    ): ReplicaDocument {
        require(validCollaborationSpaceRecipientSubmission(value))
        require(validCollaborationSpaceRecipientCoordinates(access, value.original.peerAccountId))
        val id = recipientSubmissionId(value.id)
        val dto =
            RecipientSubmissionDto(
                peer = value.original.peerAccountId,
                id = value.id,
                text = value.original.text,
                savedAt = value.original.savedAt.toString(),
                nextRevision = value.nextRevision,
                submittedAt = value.submittedAt.toString(),
            )
        val bytes = Json.encodeToString(dto).encodeToByteArray(throwOnInvalidSequence = true)
        val payload =
            try {
                require(bytes.size <= 96_000)
                Base64.encode(cipher.encrypt(key, aad(generation, id, revision), bytes)).also {
                    require(it.length <= 131_072)
                }
            } finally {
                bytes.fill(0)
            }
        return ReplicaDocument(id, revision, id, payload, setOf(RECIPIENT_OUTBOX_TAG))
    }

    suspend fun decode(
        checkpoint: ReplicaCheckpoint,
        document: ReplicaDocument,
    ): CollaborationSpaceRecipientSubmission {
        val id = document.id.removePrefix("submission:")
        require(document.id == recipientSubmissionId(id) && document.sortKey == document.id)
        require(
            document.version in 0..checkpoint.revision &&
                document.tags == setOf(RECIPIENT_OUTBOX_TAG)
        )
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
                Json.decodeFromString<RecipientSubmissionDto>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
            } finally {
                bytes.fill(0)
            }
        require(dto.schema == 1 && dto.id == id)
        return CollaborationSpaceRecipientSubmission(
                CollaborationSpaceRecipientDraft(
                    dto.peer,
                    dto.id,
                    dto.text,
                    Instant.parse(dto.savedAt),
                ),
                dto.nextRevision,
                Instant.parse(dto.submittedAt),
            )
            .also {
                require(validCollaborationSpaceRecipientSubmission(it))
                require(
                    validCollaborationSpaceRecipientCoordinates(access, it.original.peerAccountId)
                )
            }
    }

    private fun aad(generation: String, id: String, revision: Long) =
        Json.encodeToString(
                listOf(
                    "hris-space-recipient-submission-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    recipientDraftPartition(access),
                    generation,
                    id,
                    revision.toString(),
                    id,
                )
            )
            .encodeToByteArray()
}

@Serializable
private data class RecipientSubmissionDto(
    val schema: Int = 1,
    val peer: String,
    val id: String,
    val text: String,
    val savedAt: String,
    val nextRevision: String,
    val submittedAt: String,
) {
    override fun toString() = "RecipientSubmissionDto([redacted])"
}
