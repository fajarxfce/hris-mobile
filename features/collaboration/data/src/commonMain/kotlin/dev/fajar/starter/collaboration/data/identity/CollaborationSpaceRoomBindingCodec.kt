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
internal fun spaceRoomBindingPartition(access: CollaborationSpaceLocalAccess): String {
    spaceRoomBindingScope(access)
    return COLLABORATION_HISTORY_PREFIX + "space-room-bindings/v1/" + access.spaceId
}

internal fun spaceRoomBindingScope(access: CollaborationSpaceLocalAccess): ReplicaScope {
    require(validCollaborationSpaceConversationId(access.accountId))
    require(validCollaborationSpaceConversationId(access.spaceId))
    return spaceAccountScope(
        CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
    )
}

internal class CollaborationSpaceRoomBindingCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
    private val indexKey: ByteArray,
) {
    suspend fun conversationId(id: String): String {
        require(validCollaborationSpaceConversationId(id))
        return index(listOf("conversation", id))
    }

    suspend fun roomId(serverName: String, roomId: String): String {
        require(validCollaborationSpaceRoomIdentity(serverName, roomId))
        return index(listOf("room", serverName, roomId))
    }

    private suspend fun index(parts: List<String>): String {
        val bytes = context("index", parts)
        return try {
            "b-" +
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

    suspend fun ids(value: CollaborationSpaceRoomBinding): List<String> {
        require(validCollaborationSpaceRoomBinding(access, value))
        return listOf(
            conversationId(value.conversation.id),
            roomId(value.room.serverName, value.room.roomId),
        )
    }

    suspend fun checkpoint(generation: String, revision: Long): String {
        require(
            validCollaborationSpaceConversationId(generation) &&
                revision in 0..9_007_199_254_740_991L
        )
        return seal(generation, "checkpoint", revision, Json.encodeToString(BindingCheckpoint(1)))
    }

    suspend fun validate(raw: ReplicaCheckpoint) {
        require(raw.published && raw.handle.scope == spaceRoomBindingScope(access))
        require(raw.handle.partition == spaceRoomBindingPartition(access))
        require(validCollaborationSpaceConversationId(raw.handle.generation))
        require(raw.revision in 0..9_007_199_254_740_991L)
        require(
            Json.decodeFromString<BindingCheckpoint>(
                    open(raw.handle.generation, "checkpoint", raw.revision, raw.payload)
                )
                .schema == 1
        )
    }

    suspend fun documents(
        generation: String,
        revision: Long,
        value: CollaborationSpaceRoomBinding,
    ): List<ReplicaDocument> {
        require(validCollaborationSpaceRoomBinding(access, value))
        val c = value.conversation
        val room = value.room
        val payload =
            Json.encodeToString(
                BindingRecord(
                    1,
                    ConversationRecord(
                        c.spaceId,
                        c.id,
                        c.firstAccountId,
                        c.secondAccountId,
                        c.createdBy,
                        c.createdAt.toString(),
                        c.version,
                    ),
                    RoomRecord(
                        room.roomId,
                        room.serverName,
                        room.origin.name,
                        room.publishedAt.toString(),
                        room.original?.let {
                            ReferenceRecord(
                                it.position.companyId,
                                it.position.conversationId,
                                it.roomId,
                                it.boundAt.toString(),
                            )
                        },
                    ),
                    value.original?.let { original ->
                        val old = original.conversation
                        val oldRoom = original.room
                        OriginalRecord(
                            old.position.companyId,
                            old.position.conversationId,
                            old.firstAccountId,
                            old.secondAccountId,
                            old.createdBy,
                            old.createdAt.toString(),
                            old.version,
                            oldRoom.serverName,
                            oldRoom.roomId,
                            oldRoom.boundAt.toString(),
                            oldRoom.createdBy,
                        )
                    },
                )
            )
        return ids(value).map { id ->
            ReplicaDocument(id, revision, id, seal(generation, id, revision, payload), setOf(id))
        }
    }

    suspend fun decode(
        checkpoint: ReplicaCheckpoint,
        raw: ReplicaDocument,
    ): CollaborationSpaceRoomBinding {
        require(
            raw.version in 0..checkpoint.revision &&
                raw.sortKey == raw.id &&
                raw.tags == setOf(raw.id)
        )
        val value =
            Json.decodeFromString<BindingRecord>(
                open(checkpoint.handle.generation, raw.id, raw.version, requireNotNull(raw.payload))
            )
        require(value.schema == 1)
        val c = value.conversation
        val r = value.room
        val binding =
            CollaborationSpaceRoomBinding(
                CollaborationSpaceConversationMetadata(
                    c.spaceId,
                    c.id,
                    c.firstAccountId,
                    c.secondAccountId,
                    c.createdBy,
                    Instant.parse(c.createdAt),
                    c.version,
                ),
                CollaborationSpacePublishedRoom(
                    r.roomId,
                    r.serverName,
                    CollaborationSpaceRoomOrigin.valueOf(r.origin),
                    Instant.parse(r.publishedAt),
                    r.original?.let {
                        CollaborationSpaceOriginalRoom(
                            CollaborationOriginalConversationPosition(
                                it.companyId,
                                it.conversationId,
                            ),
                            it.roomId,
                            Instant.parse(it.boundAt),
                        )
                    },
                ),
                value.original?.let {
                    CollaborationSpaceOriginalRoomMetadata(
                        CollaborationOriginalConversation(
                            CollaborationOriginalConversationPosition(
                                it.companyId,
                                it.conversationId,
                            ),
                            it.firstAccountId,
                            it.secondAccountId,
                            it.createdBy,
                            Instant.parse(it.createdAt),
                            it.version,
                        ),
                        CollaborationOriginalRoom(
                            it.serverName,
                            it.roomId,
                            Instant.parse(it.boundAt),
                            it.roomCreatedBy,
                        ),
                    )
                },
            )
        require(raw.id in ids(binding))
        return binding
    }

    /** Both indexes must name the same immutable fact and original insertion revision. */
    suspend fun pair(
        checkpoint: ReplicaCheckpoint,
        documents: List<ReplicaDocument>,
    ): CollaborationSpaceRoomBinding {
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
                    "hris-space-room-binding-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    purpose,
                ) + parts
            )
            .encodeToByteArray()
}

@Serializable private data class BindingCheckpoint(val schema: Int)

@Serializable
private data class BindingRecord(
    val schema: Int,
    val conversation: ConversationRecord,
    val room: RoomRecord,
    val original: OriginalRecord?,
)

@Serializable
private data class ConversationRecord(
    val spaceId: String,
    val id: String,
    val firstAccountId: String,
    val secondAccountId: String,
    val createdBy: String,
    val createdAt: String,
    val version: Long,
)

@Serializable
private data class RoomRecord(
    val roomId: String,
    val serverName: String,
    val origin: String,
    val publishedAt: String,
    val original: ReferenceRecord?,
)

@Serializable
private data class ReferenceRecord(
    val companyId: String,
    val conversationId: String,
    val roomId: String,
    val boundAt: String,
)

@Serializable
private data class OriginalRecord(
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
