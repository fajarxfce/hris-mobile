package dev.fajar.starter.collaboration.data.readstate

import dev.fajar.starter.collaboration.data.history.*
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

internal fun readStatePartition(access: CollaborationLocalAccess, conversationId: String): String {
    historyScope(access)
    require(validCollaborationMessageId(conversationId))
    // The shared history-key absence guard must see these records, including inactive accounts.
    return COLLABORATION_HISTORY_PREFIX + "read/" + access.companyId + "/" + conversationId
}

internal fun validateReadStateRoom(roomId: String) {
    require(
        roomId.startsWith('!') &&
            roomId.length in 2..255 &&
            roomId.none { it.isWhitespace() || it.isISOControl() }
    )
}

internal fun validReadIntent(value: CollaborationReadReceiptIntent) =
    validCollaborationMessageId(value.id) && validCollaborationEventId(value.eventId)

@Serializable
internal data class ReadIntentDto(val id: String, val eventId: String, val visibility: String) {
    fun value() =
        CollaborationReadReceiptIntent(
                id,
                eventId,
                enumValueOf<CollaborationReadReceiptVisibility>(visibility),
            )
            .also { require(validReadIntent(it)) }

    override fun toString() = "ReadIntentDto([redacted])"
}

internal fun CollaborationReadReceiptIntent.dto() = ReadIntentDto(id, eventId, visibility.name)

@Serializable
internal data class ReadStateCheckpointDto(
    val count: Long,
    val pending: List<ReadIntentDto>,
    val acknowledged: ReadIntentDto?,
    val schema: Int = 1,
) {
    override fun toString() = "ReadStateCheckpointDto([redacted])"
}

internal class CollaborationReadStateCodec(
    private val access: CollaborationLocalAccess,
    private val conversationId: String,
    private val roomId: String,
    private val cipher: ContentCipher,
    private val key: ByteArray,
    private val indexKey: ByteArray,
) {
    init {
        readStatePartition(access, conversationId)
        validateReadStateRoom(roomId)
    }

    suspend fun checkpoint(raw: ReplicaCheckpoint): ReadStateCheckpointDto {
        require(
            raw.published &&
                raw.handle.scope == historyScope(access) &&
                raw.handle.partition == readStatePartition(access, conversationId)
        )
        require(
            validCollaborationMessageId(raw.handle.generation) &&
                raw.revision in 0 until 9_007_199_254_740_991L
        )
        return Json.decodeFromString<ReadStateCheckpointDto>(
                open(raw.handle.generation, "checkpoint", raw.revision, raw.payload)
            )
            .also {
                require(it.schema == 1 && it.count in 1..9_007_199_254_740_991L)
                require(it.pending.size <= 128)
                val pending = it.pending.map { entry -> entry.value() }
                require(pending.map { entry -> entry.id }.distinct().size == pending.size)
                val acknowledged = it.acknowledged?.value()
                require(
                    acknowledged == null || pending.none { entry -> entry.id == acknowledged.id }
                )
            }
    }

    suspend fun checkpoint(generation: String, revision: Long, value: ReadStateCheckpointDto) =
        seal(generation, "checkpoint", revision, Json.encodeToString(value))

    suspend fun id(eventId: String): String {
        require(validCollaborationEventId(eventId))
        val bytes =
            Json.encodeToString(
                    listOf(
                        "hris-read-index-v1",
                        access.audience,
                        access.accountId,
                        access.companyId,
                        conversationId,
                        roomId,
                        eventId,
                    )
                )
                .encodeToByteArray()
        return try {
            "read-" +
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

    suspend fun event(raw: ReplicaCheckpoint, document: ReplicaDocument): String {
        require(
            document.version in 0..raw.revision &&
                document.id.matches(Regex("read-[0-9a-f]{64}")) &&
                document.sortKey == document.id &&
                document.tags == setOf("read")
        )
        return Json.decodeFromString<String>(
                open(
                    raw.handle.generation,
                    document.id,
                    document.version,
                    requireNotNull(document.payload),
                )
            )
            .also { require(validCollaborationEventId(it) && id(it) == document.id) }
    }

    suspend fun event(generation: String, revision: Long, eventId: String): ReplicaDocument {
        val id = id(eventId)
        return ReplicaDocument(
            id,
            revision,
            id,
            seal(generation, id, revision, Json.encodeToString(eventId)),
            setOf("read"),
        )
    }

    private fun aad(generation: String, id: String, revision: Long) =
        Json.encodeToString(
                listOf(
                    "hris-read-content-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    conversationId,
                    roomId,
                    generation,
                    id,
                    revision.toString(),
                )
            )
            .encodeToByteArray()

    private suspend fun seal(
        generation: String,
        id: String,
        revision: Long,
        value: String,
    ): String {
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size <= 64_000)
            Base64.encode(cipher.encrypt(key, aad(generation, id, revision), bytes)).also {
                require(it.length <= 96_000)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun open(
        generation: String,
        id: String,
        revision: Long,
        payload: String,
    ): String {
        require(payload.length in 1..96_000)
        val bytes = cipher.decrypt(key, aad(generation, id, revision), Base64.decode(payload))
        return try {
            require(bytes.size <= 64_000)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }
}
