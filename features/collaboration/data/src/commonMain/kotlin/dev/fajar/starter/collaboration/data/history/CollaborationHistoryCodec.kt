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

internal fun validateHistoryRoom(value: String) {
    require(value.startsWith('!') && value.length in 2..255)
    require(value.none { it.isWhitespace() || it.isISOControl() })
}

internal fun historyScope(access: CollaborationLocalAccess) =
    messageScope(access).let { ReplicaScope(it.sessionId, it.accountKey) }

internal fun historyPartition(access: CollaborationLocalAccess, conversationId: String): String {
    require(validCollaborationMessageId(conversationId))
    return COLLABORATION_HISTORY_PREFIX + access.companyId + "/" + conversationId
}

/** Every visible index is recomputed and authenticated; no sender, text or event ID in indexes. */
internal class CollaborationHistoryCodec
private constructor(
    private val coordinates: CollaborationHistoryCoordinates,
    private var roomId: String?,
    private val cipher: ContentCipher,
    private val key: ByteArray,
    private val indexKey: ByteArray,
) {
    constructor(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String?,
        cipher: ContentCipher,
        key: ByteArray,
        indexKey: ByteArray,
    ) : this(
        CollaborationHistoryCoordinates.Company(access, conversationId),
        roomId,
        cipher,
        key,
        indexKey,
    )

    constructor(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        roomId: String?,
        cipher: ContentCipher,
        key: ByteArray,
        indexKey: ByteArray,
    ) : this(
        CollaborationHistoryCoordinates.Space(access, conversationId),
        roomId,
        cipher,
        key,
        indexKey,
    )

    init {
        roomId?.let(::validateHistoryRoom)
    }

    suspend fun aliasId(eventId: String): String {
        require(validCollaborationEventId(eventId))
        return "e-" + index(listOf("event", eventId))
    }

    suspend fun revisionId(eventId: String) = "r-" + aliasId(eventId).removePrefix("e-")

    fun revisionTag(messageId: String): String {
        require(historyMessageId.matches(messageId))
        return "revisions-" + messageId
    }

    suspend fun revision(checkpoint: ReplicaCheckpoint, raw: ReplicaDocument): HistoryRevisionDto {
        require(raw.version in 0..checkpoint.revision)
        val value =
            Json.decodeFromString<HistoryRevisionDto>(
                open(
                    checkpoint.handle.generation,
                    raw.id,
                    raw.version,
                    raw.sortKey,
                    requireNotNull(raw.payload),
                )
            )
        val revision = value.revision.toDomain()
        require(validCollaborationMessageEdit(revision.edit))
        require(revision.targetAcceptedAt in 0..revision.edit.timestamp)
        require(raw.id == revisionId(revision.edit.eventId))
        require(raw.sortKey == revisionSort(revision, raw.id))
        require(raw.tags == setOf(revisionTag(value.messageId)))
        return value
    }

    suspend fun revision(
        generation: String,
        version: Long,
        value: HistoryRevisionDto,
    ): ReplicaDocument {
        val revision = value.revision.toDomain()
        require(validCollaborationMessageEdit(revision.edit))
        require(revision.targetAcceptedAt in 0..revision.edit.timestamp)
        val id = revisionId(revision.edit.eventId)
        val sort = revisionSort(revision, id)
        return ReplicaDocument(
            id,
            version,
            sort,
            seal(generation, id, version, sort, Json.encodeToString(value)),
            setOf(revisionTag(value.messageId)),
        )
    }

    suspend fun editEvidenceId(eventId: String) = "p-" + aliasId(eventId).removePrefix("e-")

    suspend fun editOperationTag(sender: String, operationId: String): String {
        require(validCollaborationHistorySender(sender) && validCollaborationMessageId(operationId))
        return "edit-operation-" + index(listOf("edit-operation", sender, operationId))
    }

    suspend fun editInputFingerprint(edit: CollaborationMessageEdit): String {
        require(validCollaborationMessageEdit(edit))
        return index(
            listOf(
                "edit-evidence-input-v1",
                edit.eventId,
                edit.sender,
                edit.targetEventId,
                edit.timestamp.toString(),
                edit.text,
                edit.contentHash,
                edit.operationId.orEmpty(),
                edit.originalLogicalId.orEmpty(),
                edit.replyToEventId.orEmpty(),
                edit.forwarded.toString(),
            )
        )
    }

    suspend fun editEvidence(
        checkpoint: ReplicaCheckpoint,
        raw: ReplicaDocument,
    ): HistoryEditEvidenceDto {
        require(raw.version in 0..checkpoint.revision)
        val value =
            Json.decodeFromString<HistoryEditEvidenceDto>(
                open(
                    checkpoint.handle.generation,
                    raw.id,
                    raw.version,
                    raw.sortKey,
                    requireNotNull(raw.payload),
                )
            )
        validateEditEvidence(value)
        require(raw.id == editEvidenceId(value.eventId))
        require(raw.sortKey == editEvidenceSort(value, raw.id))
        require(raw.tags == editEvidenceTags(value))
        return value
    }

    suspend fun editEvidence(
        generation: String,
        revision: Long,
        value: HistoryEditEvidenceDto,
    ): ReplicaDocument {
        validateEditEvidence(value)
        val id = editEvidenceId(value.eventId)
        val sort = editEvidenceSort(value, id)
        return ReplicaDocument(
            id,
            revision,
            sort,
            seal(generation, id, revision, sort, Json.encodeToString(value)),
            editEvidenceTags(value),
        )
    }

    private fun validateEditEvidence(value: HistoryEditEvidenceDto) {
        require(historyMessageId.matches(value.messageId))
        require(
            validCollaborationEventId(value.eventId) &&
                validCollaborationEventId(value.targetEventId)
        )
        require(
            value.eventId != value.targetEventId && value.timestamp in 0..9_007_199_254_740_991L
        )
        require(
            validCollaborationMessageOrigin(
                CollaborationMessageOrigin(value.sender, value.contentHash)
            )
        )
        require(value.operationId == null || validCollaborationMessageId(value.operationId))
        require(value.inputFingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    private fun editEvidenceSort(value: HistoryEditEvidenceDto, id: String) =
        (9_007_199_254_740_991L - value.timestamp).toString().padStart(16, '0') + "/" + id

    private suspend fun editEvidenceTags(value: HistoryEditEvidenceDto) =
        value.operationId?.let { setOf(editOperationTag(value.sender, it)) } ?: emptySet()

    private fun revisionSort(revision: CollaborationMessageRevision, id: String) =
        (9_007_199_254_740_991L - revision.edit.timestamp).toString().padStart(16, '0') + "/" + id

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
        require(raw.published && raw.handle.scope == coordinates.scope)
        require(raw.handle.partition == coordinates.partition)
        require(validCollaborationMessageId(raw.handle.generation))
        require(raw.revision in 0 until 9_007_199_254_740_991L)
        return Json.decodeFromString<HistoryCheckpointDto>(
                open(raw.handle.generation, "checkpoint", raw.revision, "", raw.payload)
            )
            .also {
                require(
                    it.schema == 1 &&
                        (roomId == null || it.roomId == roomId) &&
                        it.count in 0..9_007_199_254_740_991L &&
                        it.hiddenRules in 0..9_007_199_254_740_991L
                )
                validateHistoryRoom(it.roomId)
                roomId = it.roomId
            }
    }

    suspend fun checkpoint(generation: String, revision: Long, count: Long, hiddenRules: Long = 0) =
        seal(
            generation,
            "checkpoint",
            revision,
            "",
            Json.encodeToString(
                HistoryCheckpointDto(requireNotNull(roomId), count, hiddenRules = hiddenRules)
            ),
        )

    suspend fun message(
        checkpoint: ReplicaCheckpoint,
        raw: ReplicaDocument,
    ): CollaborationHistoryMessage {
        require(raw.version in 0..checkpoint.revision)
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
        require(
            raw.tags ==
                setOf(if (message.locallyHidden) HISTORY_HIDDEN_MESSAGES else HISTORY_MESSAGES)
        )
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
            setOf(if (message.locallyHidden) HISTORY_HIDDEN_MESSAGES else HISTORY_MESSAGES),
        )
    }

    suspend fun hiddenId(rule: HiddenHistoryRule): String {
        require(rule.valid())
        return "h-" +
            index(
                listOf(
                    "local-hidden-v1",
                    rule.sender,
                    if (rule.logicalId == null) "event" else "logical",
                    rule.logicalId ?: requireNotNull(rule.eventId),
                )
            )
    }

    suspend fun hidden(checkpoint: ReplicaCheckpoint, raw: ReplicaDocument): HiddenHistoryRule {
        require(raw.version in 0..checkpoint.revision && raw.tags == setOf(HISTORY_HIDDEN_RULES))
        require(raw.id.matches(Regex("h-[0-9a-f]{64}")) && raw.sortKey == raw.id)
        return Json.decodeFromString<HiddenHistoryRule>(
                open(
                    checkpoint.handle.generation,
                    raw.id,
                    raw.version,
                    raw.sortKey,
                    requireNotNull(raw.payload),
                )
            )
            .also { require(it.valid() && hiddenId(it) == raw.id) }
    }

    suspend fun hidden(
        generation: String,
        revision: Long,
        rule: HiddenHistoryRule,
    ): ReplicaDocument {
        val id = hiddenId(rule)
        return ReplicaDocument(
            id,
            revision,
            id,
            seal(generation, id, revision, id, Json.encodeToString(rule)),
            setOf(HISTORY_HIDDEN_RULES),
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
        if (alias.replacement) {
            require(alias.messageId != null && alias.timestamp != null)
            require(alias.replacementHash?.matches(Regex("[0-9a-f]{64}")) == true)
            require(alias.replacementTarget?.let(::validCollaborationEventId) == true)
        } else require(alias.replacementHash == null && alias.replacementTarget == null)
    }

    private fun sort(message: CollaborationHistoryMessage, id: String) =
        (9_007_199_254_740_991L - message.timestamp).toString().padStart(16, '0') + "/" + id

    private suspend fun index(parts: List<String>): String {
        val bytes =
            Json.encodeToString(coordinates.indexFrame(requireNotNull(roomId)) + parts)
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
                coordinates.contentFrame(if (id == "checkpoint") "" else requireNotNull(roomId)) +
                    listOf(generation, id, revision.toString(), sort)
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

    private fun limit(id: String) = if (id == "checkpoint") 32_768 else 131_072
}
