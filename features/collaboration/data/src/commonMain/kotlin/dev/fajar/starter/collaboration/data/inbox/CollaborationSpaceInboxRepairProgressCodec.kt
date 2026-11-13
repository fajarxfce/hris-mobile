package dev.fajar.starter.collaboration.data.inbox

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun spaceInboxRepairProgressPartition(access: CollaborationSpaceLocalAccess): String {
    spaceHistoryScope(access)
    return COLLABORATION_HISTORY_PREFIX + "space-inbox-repair-progress/v1/" + access.spaceId
}

/** Owned finite-pass checkpoint, encrypted independently of visible inbox activity revisions. */
internal class CollaborationSpaceInboxRepairProgressCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun decode(raw: ReplicaCheckpoint): CollaborationSpaceInboxRepairProgress {
        require(
            raw.published && raw.revision == 0L && raw.handle.scope == spaceHistoryScope(access)
        )
        require(raw.handle.partition == spaceInboxRepairProgressPartition(access))
        require(
            validCollaborationMessageId(raw.handle.generation) && raw.payload.length in 1..16384
        )
        val bytes = cipher.decrypt(key, aad(raw.handle.generation), Base64.decode(raw.payload))
        return try {
            require(bytes.size in 1..8192)
            val dto =
                Json.decodeFromString<InboxRepairProgressDto>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
            require(dto.schema == 1)
            CollaborationSpaceInboxRepairProgress(
                    raw.handle.generation,
                    dto.latestDemand,
                    dto.completedDemand,
                    dto.pass?.let { CollaborationSpaceInboxRepairPass(it.demand, it.after) },
                )
                .also { require(validCollaborationSpaceInboxRepairProgress(it)) }
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun encode(value: CollaborationSpaceInboxRepairProgress): String {
        require(validCollaborationSpaceInboxRepairProgress(value))
        val dto =
            InboxRepairProgressDto(
                1,
                value.latestDemand,
                value.completedDemand,
                value.pass?.let { InboxRepairPassDto(it.demand, it.after) },
            )
        val bytes = Json.encodeToString(dto).encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size in 1..8192)
            Base64.encode(cipher.encrypt(key, aad(value.generation), bytes)).also {
                require(it.length <= 16384)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun aad(generation: String) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-space-inbox-repair-progress-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    generation,
                    "0",
                )
            )
            .encodeToByteArray()
}

@Serializable
private data class InboxRepairProgressDto(
    val schema: Int,
    val latestDemand: String,
    val completedDemand: String?,
    val pass: InboxRepairPassDto?,
)

@Serializable private data class InboxRepairPassDto(val demand: String, val after: String?)
