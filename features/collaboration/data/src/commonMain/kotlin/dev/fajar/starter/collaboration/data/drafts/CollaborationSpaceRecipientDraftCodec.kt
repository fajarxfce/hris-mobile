package dev.fajar.starter.collaboration.data.drafts

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Distinct source identity; a peer account ID is never a canonical conversation ID. */
internal fun recipientDraftPartition(access: CollaborationSpaceLocalAccess): String {
    spaceHistoryScope(access)
    return COLLABORATION_HISTORY_PREFIX + "space-recipient-drafts/v1/" + access.spaceId
}

internal class CollaborationSpaceRecipientDraftCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun checkpoint(raw: ReplicaCheckpoint) {
        require(raw.published && raw.handle.scope == spaceHistoryScope(access))
        require(raw.handle.partition == recipientDraftPartition(access))
        require(
            raw.revision in 0 until 9_007_199_254_740_991L &&
                validCollaborationMessageId(raw.handle.generation)
        )
        require(
            open(raw.handle.generation, "checkpoint", raw.revision, "", raw.payload, 32_768) ==
                "recipient-drafts-v1"
        )
    }

    suspend fun checkpoint(generation: String, revision: Long) =
        seal(generation, "checkpoint", revision, "", "recipient-drafts-v1", 32_768)

    suspend fun decode(
        checkpoint: ReplicaCheckpoint,
        document: ReplicaDocument,
    ): CollaborationSpaceRecipientDraft {
        require(validCollaborationMessageId(document.id) && document.sortKey == document.id)
        require(document.version in 0..checkpoint.revision)
        val dto =
            Json.decodeFromString<RecipientDraftDto>(
                open(
                    checkpoint.handle.generation,
                    document.id,
                    document.version,
                    document.sortKey,
                    requireNotNull(document.payload),
                    131_072,
                )
            )
        require(dto.schema == 1)
        val draft =
            CollaborationSpaceRecipientDraft(
                document.id,
                dto.revision,
                dto.text,
                Instant.parse(dto.savedAt),
            )
        require(validCollaborationSpaceRecipientDraft(draft))
        require(document.tags.isEmpty())
        return draft
    }

    suspend fun encode(
        generation: String,
        revision: Long,
        draft: CollaborationSpaceRecipientDraft,
    ): ReplicaDocument {
        require(validCollaborationSpaceRecipientDraft(draft))
        val payload =
            Json.encodeToString(
                RecipientDraftDto(
                    revision = draft.revision,
                    text = draft.text,
                    savedAt = draft.savedAt.toString(),
                )
            )
        return ReplicaDocument(
            draft.peerAccountId,
            revision,
            draft.peerAccountId,
            seal(generation, draft.peerAccountId, revision, draft.peerAccountId, payload, 131_072),
            emptySet(),
        )
    }

    private fun aad(generation: String, kind: String, revision: Long, sort: String) =
        Json.encodeToString(
                listOf(
                    "hris-space-recipient-draft-content-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    recipientDraftPartition(access),
                    generation,
                    kind,
                    revision.toString(),
                    sort,
                )
            )
            .encodeToByteArray()

    private suspend fun seal(
        generation: String,
        kind: String,
        revision: Long,
        sort: String,
        value: String,
        limit: Int,
    ): String {
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size <= 96_000)
            Base64.encode(cipher.encrypt(key, aad(generation, kind, revision, sort), bytes)).also {
                require(it.length <= limit)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun open(
        generation: String,
        kind: String,
        revision: Long,
        sort: String,
        payload: String,
        limit: Int,
    ): String {
        require(payload.length in 1..limit)
        val bytes =
            cipher.decrypt(key, aad(generation, kind, revision, sort), Base64.decode(payload))
        return try {
            require(bytes.size <= 96_000)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }
}

@Serializable
private data class RecipientDraftDto(
    val schema: Int = 1,
    val revision: String,
    val text: String,
    val savedAt: String,
) {
    override fun toString() = "RecipientDraftDto([redacted])"
}
