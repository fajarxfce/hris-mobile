package dev.fajar.starter.collaboration.data.messages

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.securestorage.ContentCipher
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun messageScope(access: CollaborationLocalAccess): CommandScope {
    require(access.sessionId.isNotBlank() && access.sessionId.length <= 128)
    require(
        validCollaborationMessageId(access.accountId) &&
            validCollaborationMessageId(access.companyId)
    )
    return CommandScope(access.sessionId, accountStorageKey(access.audience, access.accountId))
}

internal fun messagePartition(access: CollaborationLocalAccess) =
    "hris.collaboration.message/" + access.companyId

internal fun CollaborationMessageIntent.orderKey() =
    createdAt.toEpochMilliseconds().toString().padStart(15, '0') + "/" + id

@Serializable
internal data class MessageIntentDto(
    val id: String,
    val audience: String,
    val accountId: String,
    val companyId: String,
    val conversationId: String,
    val createdAt: String,
    val text: String,
    val replyToEventId: String? = null,
) {
    override fun toString() = "MessageIntentDto([redacted])"
}

@Serializable
internal data class MessageStateDto(
    val phase: String,
    val nativeSessionId: String? = null,
    val deviceId: String? = null,
    val bootstrapOperationId: String? = null,
    val roomId: String? = null,
    val before: List<String>? = null,
    val transactionId: String? = null,
    val eventId: String? = null,
    val originSender: String? = null,
    val originContentHash: String? = null,
) {
    override fun toString() = "MessageStateDto([redacted])"
}

internal fun CollaborationMessageIntent.toDto(access: CollaborationLocalAccess): MessageIntentDto {
    require(owner == access.messageOwner())
    require(validCollaborationMessageId(id) && validCollaborationMessageId(conversationId))
    require(
        createdAt.toEpochMilliseconds() in 0 until 253_402_300_800_000L &&
            validCollaborationText(text)
    )
    require(replyToEventId?.let(::validCollaborationReplyEventId) != false)
    return MessageIntentDto(
        id,
        owner.audience,
        owner.accountId,
        owner.companyId,
        conversationId,
        createdAt.toString(),
        text,
        replyToEventId,
    )
}

internal fun MessageIntentDto.toIntent(access: CollaborationLocalAccess) =
    CollaborationMessageIntent(
            id,
            CollaborationMessageOwner(audience, accountId, companyId),
            conversationId,
            Instant.parse(createdAt),
            text,
            replyToEventId,
        )
        .also { require(it.toDto(access) == this) }

internal fun CollaborationMessageState.toDto(): MessageStateDto {
    require(validCollaborationMessageState(this))
    require(this != CollaborationMessageState.Cancelled) { "Cancellation requires an archive" }
    if (this == CollaborationMessageState.Queued) return MessageStateDto("QUEUED")
    val device =
        when (this) {
            is CollaborationMessageState.Staging -> device
            is CollaborationMessageState.Staged -> device
            is CollaborationMessageState.Sent -> device
            CollaborationMessageState.Queued,
            CollaborationMessageState.Cancelled -> error("Unreachable")
        }
    return MessageStateDto(
        when (this) {
            is CollaborationMessageState.Staging -> "STAGING"
            is CollaborationMessageState.Staged -> "STAGED"
            else -> "SENT"
        },
        device.nativeSessionId,
        device.deviceId,
        device.bootstrapOperationId,
        device.roomId,
        (this as? CollaborationMessageState.Staging)?.before?.sorted(),
        when (this) {
            is CollaborationMessageState.Staged -> transactionId
            is CollaborationMessageState.Sent -> transactionId
            else -> null
        },
        (this as? CollaborationMessageState.Sent)?.eventId,
        origin?.sender,
        origin?.contentHash,
    )
}

internal fun MessageStateDto.toState(): CollaborationMessageState {
    val origin =
        if (originSender == null && originContentHash == null) null
        else
            CollaborationMessageOrigin(
                requireNotNull(originSender),
                requireNotNull(originContentHash),
            )
    val result =
        if (phase == "QUEUED") CollaborationMessageState.Queued
        else {
            val device =
                CollaborationMessageDevice(
                    requireNotNull(nativeSessionId),
                    requireNotNull(deviceId),
                    requireNotNull(bootstrapOperationId),
                    requireNotNull(roomId),
                )
            when (phase) {
                "STAGING" ->
                    CollaborationMessageState.Staging(
                        device,
                        requireNotNull(before).toSet(),
                        origin,
                    )
                "STAGED" ->
                    CollaborationMessageState.Staged(device, requireNotNull(transactionId), origin)
                "SENT" ->
                    CollaborationMessageState.Sent(
                        device,
                        requireNotNull(transactionId),
                        requireNotNull(eventId),
                        origin,
                    )
                else -> error("Unsupported message state")
            }
        }
    require(result.toDto() == this)
    return result
}

@Serializable
internal data class MessageCompletionDto(val eventIds: List<String>) {
    override fun toString() = "MessageCompletionDto([redacted])"
}

@Serializable
internal data class MessageCancellationDto(val version: Int, val kind: String) {
    override fun toString() = "MessageCancellationDto([redacted])"
}

internal class CollaborationMessageCodec(
    private val access: CollaborationLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun request(intent: CollaborationMessageIntent) =
        seal(intent.id, "request", 0, Json.encodeToString(intent.toDto(access)))

    suspend fun state(id: String, revision: Long, state: MessageStateDto) =
        seal(id, "state", revision, Json.encodeToString(state.also { it.toState() }))

    suspend fun record(raw: CommandRecord): CollaborationMessageRecord {
        require(
            raw.partition == messagePartition(access) &&
                raw.resourceId == raw.id &&
                validCollaborationMessageId(raw.id)
        )
        require(raw.revision in 0..9_007_199_254_740_991L)
        val intent =
            Json.decodeFromString<MessageIntentDto>(open(raw.id, "request", 0, raw.request))
                .toIntent(access)
        require(intent.id == raw.id && intent.orderKey() == raw.sortKey)
        val state =
            Json.decodeFromString<MessageStateDto>(open(raw.id, "state", raw.revision, raw.state))
                .toState()
        require((raw.revision == 0L) == (state == CollaborationMessageState.Queued))
        return CollaborationMessageRecord(intent, raw.revision, state)
    }

    suspend fun completion(raw: CommandRecord, eventIds: List<String>): String {
        val detached = eventIds.toList()
        require(
            collaborationCompletionReceipt(CollaborationMessageCompletion(record(raw), detached)) !=
                null
        )
        val bytes =
            Json.encodeToString(MessageCompletionDto(detached))
                .encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size <= 48_000)
            Base64.encode(cipher.encrypt(key, completionAad(raw), bytes)).also {
                require(it.length <= 65_536)
            }
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun archivedRecord(raw: CommandArchive): CollaborationMessageRecord =
        if (raw.record.revision == 0L) cancelled(raw) else requireNotNull(completion(raw)).record

    suspend fun cancellation(raw: CommandRecord): String {
        require(validCollaborationCancellation(record(raw)))
        val bytes = Json.encodeToString(MessageCancellationDto(1, "CANCELLED")).encodeToByteArray()
        return try {
            Base64.encode(cipher.encrypt(key, archiveAad(raw, "cancellation-v1"), bytes)).also {
                require(it.length <= 1_024)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun cancelled(raw: CommandArchive): CollaborationMessageRecord {
        val queued = record(raw.record)
        require(validCollaborationCancellation(queued))
        require(raw.evidence.length in 1..1_024)
        val bytes =
            cipher.decrypt(
                key,
                archiveAad(raw.record, "cancellation-v1"),
                Base64.decode(raw.evidence),
            )
        return try {
            require(bytes.size <= 1_024)
            val proof =
                Json.decodeFromString<MessageCancellationDto>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
            require(proof == MessageCancellationDto(1, "CANCELLED"))
            queued.copy(state = CollaborationMessageState.Cancelled)
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun completion(raw: CommandArchive): CollaborationMessageCompletion? {
        if (raw.record.revision == 0L) {
            cancelled(raw) // Validate the terminal proof before reporting no delivery receipt.
            return null
        }
        val record = record(raw.record)
        require(raw.evidence.length in 1..65_536)
        val bytes = cipher.decrypt(key, completionAad(raw.record), Base64.decode(raw.evidence))
        return try {
            require(bytes.size <= 48_000)
            val proof =
                Json.decodeFromString<MessageCompletionDto>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
            CollaborationMessageCompletion(record, proof.eventIds.toList()).also {
                require(collaborationCompletionReceipt(it) != null)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun completionAad(raw: CommandRecord): ByteArray {
        return archiveAad(raw, "completion")
    }

    private suspend fun archiveAad(raw: CommandRecord, purpose: String): ByteArray {
        // Bind even the retained ciphertext and ordering metadata. A proof cannot be transplanted
        // onto a different valid record with the same logical ID/revision.
        val bytes =
            Json.encodeToString(
                    listOf(
                        raw.partition,
                        raw.id,
                        raw.resourceId,
                        raw.sortKey,
                        raw.request,
                        raw.state,
                        raw.revision.toString(),
                    )
                )
                .encodeToByteArray(throwOnInvalidSequence = true)
        val digest =
            try {
                require(bytes.size <= 200_000)
                CryptographyProvider.Default.get(SHA256).hasher().hash(bytes).toHexString()
            } finally {
                bytes.fill(0)
            }
        return aad(raw.id, purpose + "-" + digest, raw.revision)
    }

    private fun aad(id: String, purpose: String, revision: Long) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-message-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    id,
                    purpose,
                    revision.toString(),
                )
            )
            .encodeToByteArray()

    private suspend fun seal(id: String, purpose: String, revision: Long, value: String): String {
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size <= 131_072)
            Base64.encode(cipher.encrypt(key, aad(id, purpose, revision), bytes)).also {
                require(it.length <= envelopeLimit(purpose))
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun open(id: String, purpose: String, revision: Long, value: String): String {
        require(value.length <= envelopeLimit(purpose))
        val bytes = cipher.decrypt(key, aad(id, purpose, revision), Base64.decode(value))
        return try {
            require(bytes.size <= 131_072)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }

    private fun envelopeLimit(purpose: String) = if (purpose == "request") 128 * 1024 else 32 * 1024
}
