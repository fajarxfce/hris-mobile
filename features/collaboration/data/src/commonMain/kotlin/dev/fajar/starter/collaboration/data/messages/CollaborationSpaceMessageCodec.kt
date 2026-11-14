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

internal fun spaceMessageScope(access: CollaborationSpaceLocalAccess): CommandScope {
    require(
        validCollaborationSpaceAccountAccess(
            CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
        )
    )
    require(
        validCollaborationSpaceConversationId(access.accountId) &&
            validCollaborationSpaceConversationId(access.spaceId)
    )
    return CommandScope(access.sessionId, accountStorageKey(access.audience, access.accountId))
}

internal fun spaceMessagePartition(access: CollaborationSpaceLocalAccess): String {
    spaceMessageScope(access)
    return "hris.collaboration.space-message/v1/" + access.spaceId
}

internal fun CollaborationSpaceMessageIntent.orderKey() =
    createdAt.epochSeconds.toString().padStart(12, '0') +
        createdAt.nanosecondsOfSecond.toString().padStart(9, '0') +
        "/" +
        id

@Serializable
internal data class SpaceMessageIntentDto(
    val schema: Int,
    val id: String,
    val audience: String,
    val accountId: String,
    val spaceId: String,
    val conversationId: String,
    val createdAt: String,
    val text: String,
    val replyToEventId: String? = null,
    val forwarded: Boolean = false,
    val editTarget: MessageEditTargetDto? = null,
    val inboxOrigin: SpaceEditInboxOriginDto? = null,
    val recipientOrigin: SpaceRecipientOriginDto? = null,
) {
    override fun toString() = "SpaceMessageIntentDto([redacted])"
}

@Serializable
internal data class SpaceRecipientOriginDto(
    val peerAccountId: String,
    val draftSavedAt: String,
    val nextRevision: String,
) {
    override fun toString() = "SpaceRecipientOriginDto([redacted])"
}

internal fun CollaborationSpaceMessageIntent.toSpaceMessageDto(
    access: CollaborationSpaceLocalAccess
): SpaceMessageIntentDto {
    require(validCollaborationSpaceMessageIntent(access, this))
    return SpaceMessageIntentDto(
        1,
        id,
        owner.audience,
        owner.accountId,
        owner.spaceId,
        conversationId,
        createdAt.toString(),
        text,
        replyToEventId,
        forwarded,
        editTarget?.toMessageEditDto(),
        inboxOrigin?.let(::SpaceEditInboxOriginDto),
        recipientOrigin?.let {
            SpaceRecipientOriginDto(it.peerAccountId, it.draftSavedAt.toString(), it.nextRevision)
        },
    )
}

internal fun SpaceMessageIntentDto.toSpaceMessageIntent(
    access: CollaborationSpaceLocalAccess
): CollaborationSpaceMessageIntent {
    require(schema == 1)
    return CollaborationSpaceMessageIntent(
            id,
            CollaborationSpaceMessageOwner(audience, accountId, spaceId),
            conversationId,
            Instant.parse(createdAt),
            text,
            replyToEventId,
            forwarded,
            editTarget?.toDomain(),
            inboxOrigin?.domain(),
            recipientOrigin?.let {
                CollaborationSpaceRecipientOrigin(
                    it.peerAccountId,
                    Instant.parse(it.draftSavedAt),
                    it.nextRevision,
                )
            },
        )
        .also { require(validCollaborationSpaceMessageIntent(access, it)) }
}

internal class CollaborationSpaceMessageCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun request(intent: CollaborationSpaceMessageIntent) =
        seal(intent.id, "request", 0, Json.encodeToString(intent.toSpaceMessageDto(access)))

    suspend fun state(id: String, revision: Long, state: MessageStateDto) =
        seal(id, "state", revision, Json.encodeToString(state.also { it.toState() }))

    suspend fun record(raw: CommandRecord): CollaborationSpaceMessageRecord {
        require(
            raw.partition == spaceMessagePartition(access) &&
                raw.resourceId == raw.id &&
                validCollaborationMessageId(raw.id)
        )
        require(raw.revision in 0..9_007_199_254_740_991L)
        val intent =
            Json.decodeFromString<SpaceMessageIntentDto>(open(raw.id, "request", 0, raw.request))
                .toSpaceMessageIntent(access)
        require(intent.id == raw.id && intent.orderKey() == raw.sortKey)
        val state =
            Json.decodeFromString<MessageStateDto>(open(raw.id, "state", raw.revision, raw.state))
                .toState()
        require((raw.revision == 0L) == (state == CollaborationMessageState.Queued))
        return CollaborationSpaceMessageRecord(intent, raw.revision, state).also {
            require(validCollaborationSpaceMessageRecord(access, it))
        }
    }

    suspend fun completion(raw: CommandRecord, eventIds: List<String>): String {
        val detached = eventIds.toList()
        require(
            validCollaborationSpaceMessageCompletion(
                access,
                CollaborationSpaceMessageCompletion(record(raw), detached),
            )
        )
        val bytes =
            Json.encodeToString(MessageCompletionDto(detached))
                .encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size <= 48_000)
            Base64.encode(cipher.encrypt(key, archiveAad(raw, "completion"), bytes)).also {
                require(it.length <= 65_536)
            }
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun archivedRecord(raw: CommandArchive): CollaborationSpaceMessageRecord =
        if (raw.record.revision == 0L) cancelled(raw) else requireNotNull(completion(raw)).record

    suspend fun cancellation(raw: CommandRecord): String {
        require(validCollaborationSpaceMessageCancellation(access, record(raw)))
        val bytes = Json.encodeToString(MessageCancellationDto(1, "CANCELLED")).encodeToByteArray()
        return try {
            Base64.encode(cipher.encrypt(key, archiveAad(raw, "cancellation-v1"), bytes)).also {
                require(it.length <= 1_024)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun cancelled(raw: CommandArchive): CollaborationSpaceMessageRecord {
        val queued = record(raw.record)
        require(validCollaborationSpaceMessageCancellation(access, queued))
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

    suspend fun completion(raw: CommandArchive): CollaborationSpaceMessageCompletion? {
        if (raw.record.revision == 0L) {
            cancelled(raw) // Validate the terminal proof before reporting no delivery receipt.
            return null
        }
        val record = record(raw.record)
        require(raw.evidence.length in 1..65_536)
        val bytes =
            cipher.decrypt(key, archiveAad(raw.record, "completion"), Base64.decode(raw.evidence))
        return try {
            require(bytes.size <= 48_000)
            val proof =
                Json.decodeFromString<MessageCompletionDto>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
            CollaborationSpaceMessageCompletion(record, proof.eventIds.toList()).also {
                require(validCollaborationSpaceMessageCompletion(access, it))
            }
        } finally {
            bytes.fill(0)
        }
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
                    "hris-collaboration-space-message-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
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
