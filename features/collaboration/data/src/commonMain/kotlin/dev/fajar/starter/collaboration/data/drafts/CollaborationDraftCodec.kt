package dev.fajar.starter.collaboration.data.drafts

import dev.fajar.starter.collaboration.data.history.historyPartition
import dev.fajar.starter.collaboration.data.history.historyScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Shares history's protected key lifetime; distinct partition and AAD separate composer records.
internal fun draftPartition(access: CollaborationLocalAccess, conversationId: String) =
    historyPartition(access, conversationId) + "/composer"

internal class CollaborationDraftCodec(
    private val access: CollaborationLocalAccess,
    private val conversationId: String,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun decode(page: ReplicaPage): CollaborationDraft {
        val raw = requireNotNull(page.checkpoint)
        require(raw.published && raw.handle.scope == historyScope(access))
        require(raw.handle.partition == draftPartition(access, conversationId))
        require(raw.revision == 0L && validCollaborationMessageId(raw.handle.generation))
        require(open(raw.handle.generation, "checkpoint", raw.payload, 32_768) == "composer-v1")
        val document = page.documents.single()
        require(
            document.id == "draft" &&
                document.sortKey == "draft" &&
                document.version == 0L &&
                document.tags.isEmpty()
        )
        val dto =
            Json.decodeFromString<DraftDto>(
                open(raw.handle.generation, "draft", requireNotNull(document.payload), 131_072)
            )
        require(dto.schema == 1)
        return CollaborationDraft(
                conversationId,
                raw.handle.generation,
                dto.text,
                Instant.parse(dto.savedAt),
                dto.submissionId,
            )
            .also { require(validCollaborationDraft(it)) }
    }

    suspend fun encode(draft: CollaborationDraft): ReplicaSnapshot {
        require(validCollaborationDraft(draft) && draft.conversationId == conversationId)
        val payload =
            Json.encodeToString(
                DraftDto(
                    text = draft.text,
                    savedAt = draft.savedAt.toString(),
                    submissionId = draft.submissionId,
                )
            )
        return ReplicaSnapshot(
            draft.revision,
            seal(draft.revision, "checkpoint", "composer-v1", 32_768),
            listOf(
                ReplicaDocument(
                    "draft",
                    0,
                    "draft",
                    seal(draft.revision, "draft", payload, 131_072),
                )
            ),
        )
    }

    private fun aad(revision: String, kind: String) =
        Json.encodeToString(
                listOf(
                    "hris-composer-content-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    conversationId,
                    draftPartition(access, conversationId),
                    revision,
                    kind,
                )
            )
            .encodeToByteArray()

    private suspend fun seal(revision: String, kind: String, value: String, limit: Int): String {
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size <= 96_000)
            Base64.encode(cipher.encrypt(key, aad(revision, kind), bytes)).also {
                require(it.length <= limit)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun open(revision: String, kind: String, payload: String, limit: Int): String {
        require(payload.length in 1..limit)
        val bytes = cipher.decrypt(key, aad(revision, kind), Base64.decode(payload))
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
    val schema: Int = 1,
    val text: String,
    val savedAt: String,
    val submissionId: String? = null,
) {
    override fun toString() = "DraftDto([redacted])"
}
