package dev.fajar.starter.collaboration.data.recovery

import dev.fajar.starter.collaboration.data.history.COLLABORATION_HISTORY_PREFIX
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val INCOMING_DEMAND_DOCUMENT = "demand"
internal const val INCOMING_DEMAND_CHECKPOINT = "incoming-demand/v1"

internal fun incomingDemandScope(access: CollaborationSpaceLocalAccess): ReplicaScope {
    require(
        validCollaborationSpaceAccountAccess(
            CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
        )
    )
    require(validCollaborationMessageId(access.spaceId))
    return recoveryScope(
        CollaborationRecoveryAccess(access.sessionId, access.audience, access.accountId)
    )
}

internal fun incomingDemandPartition(access: CollaborationSpaceLocalAccess): String {
    incomingDemandScope(access)
    return COLLABORATION_HISTORY_PREFIX + "space-incoming-demand/v1/" + access.spaceId
}

/** Strict, separately authenticated local scheduling facts; never stores message content. */
internal class CollaborationSpaceIncomingDemandCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun decode(page: ReplicaPage): CollaborationSpaceIncomingDemandSnapshot {
        val raw = requireNotNull(page.checkpoint)
        require(raw.payload == INCOMING_DEMAND_CHECKPOINT)
        val document = page.documents.single()
        require(document.id == INCOMING_DEMAND_DOCUMENT && document.version == 0L)
        require(document.sortKey == INCOMING_DEMAND_DOCUMENT && document.tags.isEmpty())
        val payload = requireNotNull(document.payload)
        require(
            raw.published && raw.revision == 0L && raw.handle.scope == incomingDemandScope(access)
        )
        require(raw.handle.partition == incomingDemandPartition(access))
        require(validCollaborationMessageId(raw.handle.generation) && payload.length in 1..131072)
        val bytes = cipher.decrypt(key, aad(raw.handle.generation), Base64.decode(payload))
        return try {
            require(bytes.size in 1..65536)
            val dto =
                Json.decodeFromString<IncomingDemandDto>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
            require(dto.schema == 1 && dto.rooms.size <= 64)
            val value =
                CollaborationSpaceIncomingDemand(
                    position(dto.nextPosition),
                    dto.rooms.map { room ->
                        CollaborationSpaceIncomingRoomDemand(
                            room.conversationId,
                            room.generation,
                            position(room.position),
                            room.headEventId,
                            CollaborationSpaceIncomingPhase.valueOf(room.phase),
                        )
                    },
                    dto.rescanGeneration,
                )
            require(validCollaborationSpaceIncomingDemand(value))
            CollaborationSpaceIncomingDemandSnapshot(raw.handle.generation, value)
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun encode(snapshot: CollaborationSpaceIncomingDemandSnapshot): String {
        require(validCollaborationMessageId(snapshot.generation))
        val value = snapshot.demand
        require(validCollaborationSpaceIncomingDemand(value))
        val dto =
            IncomingDemandDto(
                1,
                value.nextPosition.toString(),
                value.rooms.map {
                    IncomingRoomDemandDto(
                        it.conversationId,
                        it.generation,
                        it.position.toString(),
                        it.headEventId,
                        it.phase.name,
                    )
                },
                value.rescanGeneration,
            )
        val bytes = Json.encodeToString(dto).encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size in 1..65536)
            Base64.encode(cipher.encrypt(key, aad(snapshot.generation), bytes)).also {
                require(it.length <= 131072)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun position(value: String): Long {
        require(value.length in 1..16)
        return value.toLong().also { require(it.toString() == value) }
    }

    private fun aad(generation: String) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-space-incoming-demand-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    generation,
                    INCOMING_DEMAND_DOCUMENT,
                    "0",
                )
            )
            .encodeToByteArray()
}

@Serializable
private data class IncomingDemandDto(
    val schema: Int,
    val nextPosition: String,
    val rooms: List<IncomingRoomDemandDto>,
    val rescanGeneration: String?,
)

@Serializable
private data class IncomingRoomDemandDto(
    val conversationId: String,
    val generation: String,
    val position: String,
    val headEventId: String?,
    val phase: String,
)
