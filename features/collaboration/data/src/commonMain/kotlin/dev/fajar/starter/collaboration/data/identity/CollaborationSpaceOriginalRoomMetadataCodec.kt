package dev.fajar.starter.collaboration.data.identity

import dev.fajar.starter.collaboration.data.history.COLLABORATION_HISTORY_PREFIX
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// This entire namespace is covered by CollaborationHistoryKeys' retained-record inventory.
internal fun spaceOriginalRoomMetadataPartition(access: CollaborationSpaceLocalAccess): String {
    spaceOriginalRoomMetadataScope(access)
    return COLLABORATION_HISTORY_PREFIX + "space-original-room-metadata/v1/" + access.spaceId
}

internal fun spaceOriginalRoomMetadataScope(access: CollaborationSpaceLocalAccess): ReplicaScope {
    require(validCollaborationSpaceConversationId(access.accountId))
    require(validCollaborationSpaceConversationId(access.spaceId))
    return spaceAccountScope(
        CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
    )
}

internal class CollaborationSpaceOriginalRoomMetadataCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
    private val indexKey: ByteArray,
) {
    suspend fun conversationId(position: CollaborationOriginalConversationPosition): String {
        require(validCollaborationOriginalConversationPosition(position))
        return index(listOf("conversation", position.companyId, position.conversationId))
    }

    suspend fun roomId(companyId: String, serverName: String, roomId: String): String {
        require(validCollaborationOriginalPhysicalRoomQuery(companyId, serverName, roomId))
        return index(listOf("room", companyId, serverName, roomId))
    }

    private suspend fun index(parts: List<String>): String {
        val bytes = context("index", parts)
        return try {
            "o-" +
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

    suspend fun ids(value: CollaborationSpaceOriginalRoomMetadata): List<String> {
        require(validCollaborationSpaceOriginalRoomMetadata(access, value))
        return listOf(
            conversationId(value.conversation.position),
            roomId(value.conversation.position.companyId, value.room.serverName, value.room.roomId),
        )
    }

    suspend fun checkpoint(generation: String, revision: Long): String {
        require(
            validCollaborationSpaceConversationId(generation) &&
                revision in 0..9_007_199_254_740_991L
        )
        return seal(
            generation,
            "checkpoint",
            revision,
            Json.encodeToString(OriginalMetadataCheckpoint(1)),
        )
    }

    suspend fun validate(raw: ReplicaCheckpoint) {
        require(raw.published && raw.handle.scope == spaceOriginalRoomMetadataScope(access))
        require(raw.handle.partition == spaceOriginalRoomMetadataPartition(access))
        require(validCollaborationSpaceConversationId(raw.handle.generation))
        require(raw.revision in 0..9_007_199_254_740_991L)
        require(
            Json.decodeFromString<OriginalMetadataCheckpoint>(
                    open(raw.handle.generation, "checkpoint", raw.revision, raw.payload)
                )
                .schema == 1
        )
    }

    suspend fun documents(
        generation: String,
        revision: Long,
        value: CollaborationSpaceOriginalRoomMetadata,
    ): List<ReplicaDocument> {
        require(validCollaborationSpaceOriginalRoomMetadata(access, value))
        val conversation = value.conversation
        val room = value.room
        val payload =
            Json.encodeToString(
                OriginalMetadataRecord(
                    1,
                    conversation.position.companyId,
                    conversation.position.conversationId,
                    conversation.firstAccountId,
                    conversation.secondAccountId,
                    conversation.createdBy,
                    conversation.createdAt.toString(),
                    conversation.version,
                    room.serverName,
                    room.roomId,
                    room.boundAt.toString(),
                    room.createdBy,
                )
            )
        return ids(value).map { id ->
            ReplicaDocument(id, revision, id, seal(generation, id, revision, payload), setOf(id))
        }
    }

    suspend fun decode(
        checkpoint: ReplicaCheckpoint,
        raw: ReplicaDocument,
    ): CollaborationSpaceOriginalRoomMetadata {
        require(
            raw.version in 0..checkpoint.revision &&
                raw.sortKey == raw.id &&
                raw.tags == setOf(raw.id)
        )
        val record =
            Json.decodeFromString<OriginalMetadataRecord>(
                open(checkpoint.handle.generation, raw.id, raw.version, requireNotNull(raw.payload))
            )
        require(record.schema == 1)
        val value =
            CollaborationSpaceOriginalRoomMetadata(
                CollaborationOriginalConversation(
                    CollaborationOriginalConversationPosition(
                        record.companyId,
                        record.conversationId,
                    ),
                    record.firstAccountId,
                    record.secondAccountId,
                    record.createdBy,
                    Instant.parse(record.createdAt),
                    record.version,
                ),
                CollaborationOriginalRoom(
                    record.serverName,
                    record.roomId,
                    Instant.parse(record.boundAt),
                    record.roomCreatedBy,
                ),
            )
        require(raw.id in ids(value))
        return value
    }

    /** Both indexes must name the same immutable fact and original insertion revision. */
    suspend fun pair(
        checkpoint: ReplicaCheckpoint,
        documents: List<ReplicaDocument>,
    ): CollaborationSpaceOriginalRoomMetadata {
        require(
            documents.size == 2 &&
                documents[0].id != documents[1].id &&
                documents[0].version == documents[1].version
        )
        val first = decode(checkpoint, documents[0])
        require(
            decode(checkpoint, documents[1]) == first &&
                documents.map { it.id }.toSet() == ids(first).toSet()
        )
        return first
    }

    private suspend fun seal(
        generation: String,
        id: String,
        revision: Long,
        value: String,
    ): String {
        val bytes = value.encodeToByteArray()
        return try {
            require(bytes.size in 1..8192)
            Base64.encode(
                    cipher.encrypt(
                        key,
                        context("content", listOf(generation, id, revision.toString())),
                        bytes,
                    )
                )
                .also { require(it.length <= 16384) }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun open(
        generation: String,
        id: String,
        revision: Long,
        value: String,
    ): String {
        require(value.length in 1..16384)
        val bytes =
            cipher.decrypt(
                key,
                context("content", listOf(generation, id, revision.toString())),
                Base64.decode(value),
            )
        return try {
            require(bytes.size in 1..8192)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }

    private fun context(purpose: String, parts: List<String>) =
        Json.encodeToString(
                listOf(
                    "hris-space-original-room-metadata-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    purpose,
                ) + parts
            )
            .encodeToByteArray()
}

@Serializable private data class OriginalMetadataCheckpoint(val schema: Int)

@Serializable
private data class OriginalMetadataRecord(
    val schema: Int,
    val companyId: String,
    val conversationId: String,
    val firstAccountId: String,
    val secondAccountId: String,
    val createdBy: String,
    val createdAt: String,
    val version: Long,
    val serverName: String,
    val roomId: String,
    val boundAt: String,
    val roomCreatedBy: String,
)
