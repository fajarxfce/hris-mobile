package dev.fajar.starter.collaboration.data.recovery

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun roomRecoveryPartition(access: CollaborationLocalAccess): String {
    require(validCollaborationMessageId(access.companyId))
    return COLLABORATION_HISTORY_PREFIX + "room-recovery/" + access.companyId
}

internal class CollaborationRoomRecoveryCodec(
    private val access: CollaborationLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun decode(raw: ReplicaCheckpoint): CollaborationRoomRecoveryProgress {
        require(raw.published && raw.revision == 0L && raw.handle.scope == historyScope(access))
        require(raw.handle.partition == roomRecoveryPartition(access))
        require(validCollaborationMessageId(raw.handle.generation) && raw.payload.length in 1..2048)
        val bytes = cipher.decrypt(key, aad(raw.handle.generation), Base64.decode(raw.payload))
        return try {
            require(bytes.size in 1..1024)
            val dto =
                Json.decodeFromString<RoomProgressDto>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
            require(dto.schema == 1)
            CollaborationRoomRecoveryProgress(
                    raw.handle.generation,
                    CollaborationRoomRecoveryPosition(
                        dto.afterConversationId,
                        dto.inFlightConversationId,
                        dto.retryRequired,
                        dto.continuationRequired,
                    ),
                )
                .also { require(validCollaborationRoomRecoveryProgress(it)) }
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun encode(value: CollaborationRoomRecoveryProgress): String {
        historyScope(access)
        roomRecoveryPartition(access)
        require(validCollaborationRoomRecoveryProgress(value))
        val p = value.position
        val bytes =
            Json.encodeToString(
                    RoomProgressDto(
                        1,
                        p.afterConversationId,
                        p.inFlightConversationId,
                        p.retryRequired,
                        p.continuationRequired,
                    )
                )
                .encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size in 1..1024)
            Base64.encode(cipher.encrypt(key, aad(value.generation), bytes)).also {
                require(it.length <= 2048)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun aad(generation: String) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-room-recovery-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    generation,
                    "0",
                )
            )
            .encodeToByteArray()
}

@Serializable
private data class RoomProgressDto(
    val schema: Int,
    val afterConversationId: String?,
    val inFlightConversationId: String?,
    val retryRequired: Boolean,
    val continuationRequired: Boolean,
)
