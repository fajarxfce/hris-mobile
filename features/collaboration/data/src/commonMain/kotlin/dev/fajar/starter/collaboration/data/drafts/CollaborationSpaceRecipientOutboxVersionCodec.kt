package dev.fajar.starter.collaboration.data.drafts

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationMessageId
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val RECIPIENT_OUTBOX_VERSION_ID = "outbox-version:v1"

/** Authenticated source-change metadata. Editor ciphertext and original payloads stay separate. */
internal class CollaborationSpaceRecipientOutboxVersionCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun decode(
        checkpoint: ReplicaCheckpoint,
        document: ReplicaDocument?,
    ): CollaborationSpaceRecipientOutboxVersion {
        CollaborationSpaceRecipientDraftCodec(access, cipher, key).checkpoint(checkpoint)
        // Old partitions have no marker. Their checkpoint is a conservative initial source token.
        if (document == null)
            return CollaborationSpaceRecipientOutboxVersion(
                checkpoint.handle.generation,
                checkpoint.revision,
            )
        require(document.id == RECIPIENT_OUTBOX_VERSION_ID && document.sortKey == document.id)
        require(document.tags.isEmpty() && document.version in 0..checkpoint.revision)
        val payload = requireNotNull(document.payload)
        require(payload.length in 1..4096)
        val bytes =
            cipher.decrypt(
                key,
                aad(checkpoint.handle.generation, document.version),
                Base64.decode(payload),
            )
        val dto =
            try {
                require(bytes.size <= 2048)
                Json.decodeFromString<RecipientOutboxVersionDto>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
            } finally {
                bytes.fill(0)
            }
        require(dto.schema == 1 && (dto.revision == null || dto.revision in 0..document.version))
        return CollaborationSpaceRecipientOutboxVersion(checkpoint.handle.generation, dto.revision)
    }

    suspend fun encode(generation: String, revision: Long, sourceRevision: Long?): ReplicaDocument {
        require(
            validCollaborationMessageId(generation) && revision in 0 until 9_007_199_254_740_991L
        )
        require(sourceRevision == null || sourceRevision in 0..revision)
        val bytes =
            Json.encodeToString(RecipientOutboxVersionDto(revision = sourceRevision))
                .encodeToByteArray()
        val payload =
            try {
                Base64.encode(cipher.encrypt(key, aad(generation, revision), bytes))
            } finally {
                bytes.fill(0)
            }
        return ReplicaDocument(
            RECIPIENT_OUTBOX_VERSION_ID,
            revision,
            RECIPIENT_OUTBOX_VERSION_ID,
            payload,
        )
    }

    private fun aad(generation: String, revision: Long) =
        Json.encodeToString(
                listOf(
                    "hris-space-recipient-outbox-version-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    recipientDraftPartition(access),
                    generation,
                    RECIPIENT_OUTBOX_VERSION_ID,
                    revision.toString(),
                )
            )
            .encodeToByteArray()
}

@Serializable
private data class RecipientOutboxVersionDto(val schema: Int = 1, val revision: Long?) {
    override fun toString() = "RecipientOutboxVersionDto([redacted])"
}
