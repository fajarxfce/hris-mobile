package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val HISTORY_REFERENCES = "references"
internal val historyReferenceId = Regex("r-[0-9a-f]{64}")
internal val historyReferenceCursor = Regex("[0-9]{16}/r-[0-9a-f]{64}")

internal fun traversalPartition(
    access: CollaborationLocalAccess,
    traversal: CollaborationHistoryTraversal,
): String {
    require(validCollaborationHistoryTraversal(traversal))
    return historyPartition(access, traversal.conversationId) + "/pages/" + traversal.deviceId
}

@Serializable
private data class HistoryProgressDto(
    val roomId: String,
    val next: String?,
    val references: Long,
    val schema: Int = 1,
)

@Serializable
private data class HistoryReferenceDto(
    val sequence: Long,
    val eventId: String,
    val sender: String,
    val timestamp: Long,
    val type: String,
    val resolved: Boolean = false,
)

/** Device/room-bound AEAD with separate keyed indexes; no provider IDs or cursors in plaintext. */
internal class CollaborationHistoryTraversalCodec(
    private val access: CollaborationLocalAccess,
    private val traversal: CollaborationHistoryTraversal,
    private val cipher: ContentCipher,
    private val key: ByteArray,
    private val indexKey: ByteArray,
) {
    init {
        historyScope(access)
        traversalPartition(access, traversal)
    }

    suspend fun progress(raw: ReplicaCheckpoint): CollaborationHistoryProgress {
        require(raw.published && raw.handle.scope == historyScope(access))
        require(raw.handle.partition == traversalPartition(access, traversal))
        require(
            validCollaborationMessageId(raw.handle.generation) &&
                raw.revision in 0 until 9_007_199_254_740_991L
        )
        val value =
            Json.decodeFromString<HistoryProgressDto>(
                open(raw.handle.generation, "checkpoint", raw.revision, "", raw.payload)
            )
        require(value.schema == 1 && value.roomId == traversal.roomId)
        require(value.next == null || validCollaborationProviderPosition(value.next))
        require(value.references in 0..9_007_199_254_740_991L)
        return CollaborationHistoryProgress(
            raw.handle.generation,
            raw.revision,
            value.next,
            value.references,
        )
    }

    suspend fun checkpoint(progress: CollaborationHistoryProgress): String {
        val next = progress.next
        require(
            validCollaborationMessageId(progress.generation) &&
                progress.revision in 0 until 9_007_199_254_740_991L
        )
        require(
            progress.references in 0..9_007_199_254_740_991L &&
                (next == null || validCollaborationProviderPosition(next))
        )
        return seal(
            progress.generation,
            "checkpoint",
            progress.revision,
            "",
            Json.encodeToString(
                HistoryProgressDto(traversal.roomId, progress.next, progress.references)
            ),
        )
    }

    suspend fun id(eventId: String): String {
        require(validCollaborationEventId(eventId))
        val bytes =
            Json.encodeToString(context() + listOf("reference-index", eventId)).encodeToByteArray()
        return try {
            "r-" +
                CryptographyProvider.Default.get(HMAC)
                    .keyDecoder(SHA256)
                    .decodeFromByteArray(HMAC.Key.Format.RAW, indexKey)
                    .signatureGenerator()
                    .generateSignature(bytes)
                    .toHexString()
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun reference(
        checkpoint: ReplicaCheckpoint,
        raw: ReplicaDocument,
    ): CollaborationHistoryReference {
        require(raw.version in 0..checkpoint.revision)
        require(historyReferenceId.matches(raw.id) && historyReferenceCursor.matches(raw.sortKey))
        val value =
            Json.decodeFromString<HistoryReferenceDto>(
                open(
                    checkpoint.handle.generation,
                    raw.id,
                    raw.version,
                    raw.sortKey,
                    requireNotNull(raw.payload),
                )
            )
        val event =
            CollaborationProviderHistoryEvent(
                value.eventId,
                value.sender,
                value.timestamp,
                value.type,
            )
        require(raw.tags == if (value.resolved) emptySet<String>() else setOf(HISTORY_REFERENCES))
        require(
            validCollaborationProviderEvent(event) &&
                value.sequence in 0 until 9_007_199_254_740_991L
        )
        require(raw.id == id(value.eventId) && raw.sortKey == sort(value.sequence, raw.id))
        return CollaborationHistoryReference(value.sequence, event, value.resolved)
    }

    suspend fun reference(
        progress: CollaborationHistoryProgress,
        value: CollaborationHistoryReference,
    ): ReplicaDocument {
        require(
            validCollaborationProviderEvent(value.event) &&
                value.sequence in 0 until progress.references
        )
        val id = id(value.event.eventId)
        val sort = sort(value.sequence, id)
        val event = value.event
        return ReplicaDocument(
            id,
            progress.revision,
            sort,
            seal(
                progress.generation,
                id,
                progress.revision,
                sort,
                Json.encodeToString(
                    HistoryReferenceDto(
                        value.sequence,
                        event.eventId,
                        event.sender,
                        event.timestamp,
                        event.type,
                        value.resolved,
                    )
                ),
            ),
            if (value.resolved) emptySet() else setOf(HISTORY_REFERENCES),
        )
    }

    private fun sort(sequence: Long, id: String) = sequence.toString().padStart(16, '0') + "/" + id

    private fun context() =
        listOf(
            "hris-history-pages-v1",
            access.audience,
            access.accountId,
            access.companyId,
            traversal.conversationId,
            traversal.roomId,
            traversal.deviceId,
        )

    private fun aad(generation: String, id: String, revision: Long, sort: String) =
        Json.encodeToString(context() + listOf(generation, id, revision.toString(), sort))
            .encodeToByteArray()

    private suspend fun seal(
        generation: String,
        id: String,
        revision: Long,
        sort: String,
        value: String,
    ): String {
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size <= 16_384)
            Base64.encode(cipher.encrypt(key, aad(generation, id, revision, sort), bytes)).also {
                require(it.length <= 32_768)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun open(
        generation: String,
        id: String,
        revision: Long,
        sort: String,
        value: String,
    ): String {
        require(value.length in 1..32_768)
        val bytes = cipher.decrypt(key, aad(generation, id, revision, sort), Base64.decode(value))
        return try {
            require(bytes.size <= 16_384)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }
}
