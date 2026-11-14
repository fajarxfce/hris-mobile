package dev.fajar.starter.collaboration.data.inbox

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

internal fun spaceInboxHistoryProgressPartition(access: CollaborationSpaceLocalAccess): String {
    spaceHistoryScope(access)
    return COLLABORATION_HISTORY_PREFIX + "space-inbox-history-owner/v1/" + access.spaceId
}

/** Bounded owner state authenticated against exact account, Group, generation and revision. */
internal class CollaborationSpaceInboxHistoryProgressCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun decode(raw: ReplicaCheckpoint): CollaborationSpaceInboxHistoryCheckpoint {
        require(raw.published && raw.handle.scope == spaceHistoryScope(access))
        require(raw.handle.partition == spaceInboxHistoryProgressPartition(access))
        require(validCollaborationMessageId(raw.handle.generation))
        require(
            raw.revision in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER && raw.payload.length in 1..32768
        )
        val version =
            CollaborationSpaceInboxHistoryProgressVersion(raw.handle.generation, raw.revision)
        val aad = context(version)
        val bytes =
            try {
                cipher.decrypt(key, aad, Base64.decode(raw.payload))
            } finally {
                aad.fill(0)
            }
        return try {
            require(bytes.size in 1..16384)
            val dto =
                Json.decodeFromString<InboxHistoryProgressDto>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
            CollaborationSpaceInboxHistoryCheckpoint(version, dto.domain())
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun encode(value: CollaborationSpaceInboxHistoryCheckpoint): String {
        require(validCollaborationMessageId(value.version.generation))
        require(value.version.revision in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER)
        require(validCollaborationSpaceInboxHistoryProgress(value.progress))
        val bytes =
            Json.encodeToString(InboxHistoryProgressDto(value.progress))
                .encodeToByteArray(throwOnInvalidSequence = true)
        val aad = context(value.version)
        return try {
            require(bytes.size in 1..16384)
            Base64.encode(cipher.encrypt(key, aad, bytes)).also { require(it.length <= 32768) }
        } finally {
            bytes.fill(0)
            aad.fill(0)
        }
    }

    private fun context(version: CollaborationSpaceInboxHistoryProgressVersion) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-space-inbox-history-owner-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    version.generation,
                    version.revision.toString(),
                )
            )
            .encodeToByteArray()
}
