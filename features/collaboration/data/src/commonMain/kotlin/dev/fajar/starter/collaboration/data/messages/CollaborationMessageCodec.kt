package dev.fajar.starter.collaboration.data.messages

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.securestorage.ContentCipher
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
    return MessageIntentDto(
        id,
        owner.audience,
        owner.accountId,
        owner.companyId,
        conversationId,
        createdAt.toString(),
        text,
    )
}

internal fun MessageIntentDto.toIntent(access: CollaborationLocalAccess) =
    CollaborationMessageIntent(
            id,
            CollaborationMessageOwner(audience, accountId, companyId),
            conversationId,
            Instant.parse(createdAt),
            text,
        )
        .also { require(it.toDto(access) == this) }

internal fun CollaborationMessageState.toDto(): MessageStateDto {
    require(validCollaborationMessageState(this))
    if (this == CollaborationMessageState.Queued) return MessageStateDto("QUEUED")
    val device =
        when (this) {
            is CollaborationMessageState.Staging -> device
            is CollaborationMessageState.Staged -> device
            is CollaborationMessageState.Sent -> device
            CollaborationMessageState.Queued -> error("Unreachable")
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
    )
}

internal fun MessageStateDto.toState(): CollaborationMessageState {
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
                    CollaborationMessageState.Staging(device, requireNotNull(before).toSet())
                "STAGED" -> CollaborationMessageState.Staged(device, requireNotNull(transactionId))
                "SENT" ->
                    CollaborationMessageState.Sent(
                        device,
                        requireNotNull(transactionId),
                        requireNotNull(eventId),
                    )
                else -> error("Unsupported message state")
            }
        }
    require(result.toDto() == this)
    return result
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
