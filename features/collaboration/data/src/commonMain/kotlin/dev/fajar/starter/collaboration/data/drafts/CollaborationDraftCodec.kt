package dev.fajar.starter.collaboration.data.drafts

import dev.fajar.starter.collaboration.data.history.COLLABORATION_HISTORY_PREFIX
import dev.fajar.starter.collaboration.data.history.historyScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// One company partition keeps each frozen body and its prepared index in the same transaction.
internal fun draftPartition(access: CollaborationLocalAccess): String {
    require(validCollaborationMessageId(access.companyId))
    return COLLABORATION_HISTORY_PREFIX + "composer/" + access.companyId
}

internal const val PREPARED_DRAFT_TAG = "prepared"

internal class CollaborationDraftCodec(
    private val access: CollaborationLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun checkpoint(raw: ReplicaCheckpoint) {
        require(raw.published && raw.handle.scope == historyScope(access))
        require(raw.handle.partition == draftPartition(access))
        require(
            raw.revision in 0 until 9_007_199_254_740_991L &&
                validCollaborationMessageId(raw.handle.generation)
        )
        require(
            open(raw.handle.generation, "checkpoint", raw.revision, "", raw.payload, 32_768) ==
                "composer-v2"
        )
    }

    suspend fun checkpoint(generation: String, revision: Long) =
        seal(generation, "checkpoint", revision, "", "composer-v2", 32_768)

    suspend fun decode(
        checkpoint: ReplicaCheckpoint,
        document: ReplicaDocument,
    ): CollaborationDraft {
        require(validCollaborationMessageId(document.id) && document.sortKey == document.id)
        require(document.version in 0..checkpoint.revision)
        val dto =
            Json.decodeFromString<DraftDto>(
                open(
                    checkpoint.handle.generation,
                    document.id,
                    document.version,
                    document.sortKey,
                    requireNotNull(document.payload),
                    131_072,
                )
            )
        require(dto.schema == 2)
        val draft =
            CollaborationDraft(
                document.id,
                dto.revision,
                dto.text,
                Instant.parse(dto.savedAt),
                dto.submissionId,
                dto.reviewRequired,
                dto.replyToEventId,
            )
        require(validCollaborationDraft(draft))
        require(
            document.tags ==
                if (draft.submissionId == null || draft.reviewRequired) emptySet<String>()
                else setOf(PREPARED_DRAFT_TAG)
        )
        return draft
    }

    suspend fun encode(
        generation: String,
        revision: Long,
        draft: CollaborationDraft,
    ): ReplicaDocument {
        require(validCollaborationDraft(draft))
        val payload =
            Json.encodeToString(
                DraftDto(
                    revision = draft.revision,
                    text = draft.text,
                    savedAt = draft.savedAt.toString(),
                    submissionId = draft.submissionId,
                    reviewRequired = draft.reviewRequired,
                    replyToEventId = draft.replyToEventId,
                )
            )
        return ReplicaDocument(
            draft.conversationId,
            revision,
            draft.conversationId,
            seal(
                generation,
                draft.conversationId,
                revision,
                draft.conversationId,
                payload,
                131_072,
            ),
            if (draft.submissionId == null || draft.reviewRequired) emptySet()
            else setOf(PREPARED_DRAFT_TAG),
        )
    }

    private fun aad(generation: String, kind: String, revision: Long, sort: String) =
        Json.encodeToString(
                listOf(
                    "hris-composer-content-v2",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    draftPartition(access),
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
private data class DraftDto(
    val schema: Int = 2,
    val revision: String,
    val text: String,
    val savedAt: String,
    val submissionId: String? = null,
    val reviewRequired: Boolean = false,
    val replyToEventId: String? = null,
) {
    override fun toString() = "DraftDto([redacted])"
}
