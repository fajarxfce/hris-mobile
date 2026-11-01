package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.data.messages.messageScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

internal const val COLLABORATION_HISTORY_PREFIX = "collaboration-history/"
internal const val HISTORY_MESSAGES = "messages"
internal val historyMessageId = Regex("m-[0-9a-f]{64}")
internal val historyCursor = Regex("[0-9]{16}/m-[0-9a-f]{64}")

internal fun historyScope(access: CollaborationLocalAccess) =
    messageScope(access).let { ReplicaScope(it.sessionId, it.accountKey) }

internal fun historyPartition(access: CollaborationLocalAccess, conversationId: String): String {
    require(validCollaborationMessageId(conversationId))
    return COLLABORATION_HISTORY_PREFIX + access.companyId + "/" + conversationId
}

/** Every visible index is recomputed and authenticated; no sender, text or event ID in indexes. */
internal class CollaborationHistoryCodec(
    private val access: CollaborationLocalAccess,
    private val conversationId: String,
    private var roomId: String?,
    private val cipher: ContentCipher,
    private val key: ByteArray,
    private val indexKey: ByteArray,
) {
    init {
        historyScope(access)
        historyPartition(access, conversationId)
        roomId?.let(::validateRoom)
    }

    suspend fun aliasId(eventId: String): String {
        require(validCollaborationEventId(eventId))
        return "e-" + index(listOf("event", eventId))
    }

    suspend fun messageId(sender: String, logicalId: String?, eventId: String?): String {
        require(validCollaborationHistorySender(sender))
        require(logicalId == null || validCollaborationMessageId(logicalId))
        require(logicalId != null || (eventId != null && validCollaborationEventId(eventId)))
        return "m-" +
            index(
                listOf(
                    sender,
                    if (logicalId == null) "event" else "logical",
                    logicalId ?: requireNotNull(eventId),
                )
            )
    }

    suspend fun checkpoint(raw: ReplicaCheckpoint): HistoryCheckpointDto {
        require(raw.published && raw.handle.scope == historyScope(access))
        require(raw.handle.partition == historyPartition(access, conversationId))
        require(validCollaborationMessageId(raw.handle.generation))
        require(raw.revision in 0 until 9_007_199_254_740_991L)
        return Json.decodeFromString<HistoryCheckpointDto>(
                open(raw.handle.generation, "checkpoint", raw.revision, "", raw.payload)
            )
            .also {
                require(
                    it.schema == 1 &&
                        (roomId == null || it.roomId == roomId) &&
                        it.count in 0..9_007_199_254_740_991L
                )
                validateRoom(it.roomId)
                roomId = it.roomId
            }
    }

    suspend fun checkpoint(generation: String, revision: Long, count: Long) =
        seal(
            generation,
            "checkpoint",
            revision,
            "",
            Json.encodeToString(HistoryCheckpointDto(requireNotNull(roomId), count)),
        )

    suspend fun message(
        checkpoint: ReplicaCheckpoint,
        raw: ReplicaDocument,
    ): CollaborationHistoryMessage {
        require(raw.version in 0..checkpoint.revision && raw.tags == setOf(HISTORY_MESSAGES))
        require(historyMessageId.matches(raw.id) && historyCursor.matches(raw.sortKey))
        val message =
            Json.decodeFromString<HistoryMessageDto>(
                    open(
                        checkpoint.handle.generation,
                        raw.id,
                        raw.version,
                        raw.sortKey,
                        requireNotNull(raw.payload),
                    )
                )
                .toDomain()
        require(validCollaborationHistoryMessage(message))
        require(raw.id == messageId(message.sender, message.logicalId, message.primaryEventId))
        require(raw.sortKey == sort(message, raw.id))
        return message
    }

    suspend fun message(
        generation: String,
        revision: Long,
        message: CollaborationHistoryMessage,
    ): ReplicaDocument {
        require(validCollaborationHistoryMessage(message))
        val id = messageId(message.sender, message.logicalId, message.primaryEventId)
        val sort = sort(message, id)
        return ReplicaDocument(
            id,
            revision,
            sort,
            seal(generation, id, revision, sort, Json.encodeToString(message.toHistoryDto())),
            setOf(HISTORY_MESSAGES),
        )
    }

    suspend fun alias(checkpoint: ReplicaCheckpoint, raw: ReplicaDocument): HistoryAliasDto {
        require(
            raw.version in 0..checkpoint.revision && raw.tags.isEmpty() && raw.sortKey == raw.id
        )
        val alias =
            Json.decodeFromString<HistoryAliasDto>(
                open(
                    checkpoint.handle.generation,
                    raw.id,
                    raw.version,
                    raw.sortKey,
                    requireNotNull(raw.payload),
                )
            )
        validate(alias)
        require(raw.id == aliasId(alias.eventId))
        return alias
    }

    suspend fun alias(generation: String, revision: Long, alias: HistoryAliasDto): ReplicaDocument {
        validate(alias)
        val id = aliasId(alias.eventId)
        return ReplicaDocument(
            id,
            revision,
            id,
            seal(generation, id, revision, id, Json.encodeToString(alias)),
        )
    }

    private fun validate(alias: HistoryAliasDto) {
        require(
            validCollaborationEventId(alias.eventId) &&
                validCollaborationHistorySender(alias.sender)
        )
        require(alias.messageId == null || historyMessageId.matches(alias.messageId))
        require(alias.timestamp == null || alias.timestamp in 0..9_007_199_254_740_991L)
        require(
            if (alias.messageId == null) alias.redacted && alias.timestamp == null
            else alias.timestamp != null
        )
    }

    private fun sort(message: CollaborationHistoryMessage, id: String) =
        (9_007_199_254_740_991L - message.timestamp).toString().padStart(16, '0') + "/" + id

    private suspend fun index(parts: List<String>): String {
        val bytes =
            Json.encodeToString(
                    listOf(
                        "hris-history-index-v1",
                        access.audience,
                        access.accountId,
                        access.companyId,
                        conversationId,
                        requireNotNull(roomId),
                    ) + parts
                )
                .encodeToByteArray()
        return try {
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

    private fun aad(generation: String, id: String, revision: Long, sort: String) =
        Json.encodeToString(
                listOf(
                    "hris-history-content-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    conversationId,
                    if (id == "checkpoint") "" else requireNotNull(roomId),
                    generation,
                    id,
                    revision.toString(),
                    sort,
                )
            )
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
            require(bytes.size <= 96_000)
            Base64.encode(cipher.encrypt(key, aad(generation, id, revision, sort), bytes)).also {
                require(it.length <= limit(id))
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
        payload: String,
    ): String {
        require(payload.length in 1..limit(id))
        val bytes = cipher.decrypt(key, aad(generation, id, revision, sort), Base64.decode(payload))
        return try {
            require(bytes.size <= 96_000)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }

    private fun validateRoom(value: String) {
        require(value.startsWith('!') && value.length in 2..255)
        require(value.none { it.isWhitespace() || it.isISOControl() })
    }

    private fun limit(id: String) = if (id == "checkpoint") 32_768 else 131_072
}
