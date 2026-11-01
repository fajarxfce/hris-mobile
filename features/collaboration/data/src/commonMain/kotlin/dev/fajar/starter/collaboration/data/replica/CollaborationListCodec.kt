@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.replica

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val COLLABORATION_LIST_PREFIX = "collaboration-list/"
internal const val COLLABORATION_LIST_ACCESS_PREFIX = "collaboration-list-access/"
internal const val COLLABORATION_LIST_CAPACITY = 10_000

internal fun listScope(access: CollaborationLocalAccess) =
    ReplicaScope(access.sessionId, accountStorageKey(access.audience, access.accountId))

internal fun listPartition(access: CollaborationLocalAccess) =
    COLLABORATION_LIST_PREFIX + collaborationId(access.companyId)

internal fun listAdmissionPartition(access: CollaborationLocalAccess) =
    COLLABORATION_LIST_ACCESS_PREFIX + collaborationId(access.companyId)

internal fun listAdmissionKey(access: CollaborationLocalAccess) =
    Json.encodeToString(listOf(access.audience, access.accountId, access.companyId))

@Serializable
internal data class CollaborationListCheckpointDto(
    val count: Int,
    val acquiredAt: String,
    val schema: Int = 1,
)

@Serializable
data class CollaborationListAdmissionDto(val revision: String, val kind: String, val code: String?)

internal fun CollaborationListAdmissionDto.toAdmission(): CollaborationListAdmission {
    collaborationId(revision)
    require(code == null || (code.length in 1..128 && code.all { it in '!'..'~' }))
    return CollaborationListAdmission(
        revision,
        Failure(
            FailureKind.valueOf(kind),
            "Conversation access requires verification.",
            code = code,
        ),
    )
}

internal fun CollaborationDirectSummary.toListDto() =
    CollaborationDirectSummaryDto(
        conversation.let {
            CollaborationDirectDto(
                it.id,
                it.companyId,
                it.firstAccountId,
                it.secondAccountId,
                it.createdBy,
                Instant.fromEpochMilliseconds(it.createdAt).toString(),
                it.version,
            )
        },
        CollaborationParticipantDto(peer.accountId, peer.displayName),
    )

/** Local identity and visible indexes are authenticated; login tokens/families are excluded. */
internal class CollaborationListCodec(
    private val cipher: ContentCipher,
    private val key: ByteArray,
    private val access: CollaborationLocalAccess,
) {
    suspend fun checkpoint(raw: ReplicaCheckpoint): CollaborationListCheckpoint {
        require(
            raw.handle.scope == listScope(access) &&
                raw.handle.partition == listPartition(access) &&
                raw.published
        )
        collaborationId(raw.handle.generation)
        require(raw.revision in 0..9_007_199_254_740_991L)
        val dto =
            Json.decodeFromString<CollaborationListCheckpointDto>(
                open(raw.handle.generation, "checkpoint", raw.revision, "", raw.payload)
            )
        require(dto.schema == 1 && dto.count in 0..COLLABORATION_LIST_CAPACITY)
        val acquired = Instant.parse(dto.acquiredAt)
        require(acquired.toEpochMilliseconds() >= 0)
        return CollaborationListCheckpoint(raw.handle.generation, raw.revision, dto.count, acquired)
    }

    suspend fun checkpoint(value: CollaborationListCheckpoint) =
        seal(
            value.generation,
            "checkpoint",
            value.revision,
            "",
            Json.encodeToString(
                CollaborationListCheckpointDto(value.count, value.acquiredAt.toString())
            ),
        )

    suspend fun document(
        checkpoint: CollaborationListCheckpoint,
        raw: ReplicaDocument,
    ): CollaborationDirectSummary {
        collaborationId(raw.id)
        require(
            raw.sortKey == raw.id && raw.tags.isEmpty() && raw.version in 0..checkpoint.revision
        )
        val dto =
            Json.decodeFromString<CollaborationDirectSummaryDto>(
                open(
                    checkpoint.generation,
                    raw.id,
                    raw.version,
                    raw.sortKey,
                    requireNotNull(raw.payload),
                )
            )
        return dto.toSummary(access.accountId, access.companyId).also {
            require(it.conversation.id == raw.id)
        }
    }

    suspend fun document(
        checkpoint: CollaborationListCheckpoint,
        summary: CollaborationDirectSummary,
    ): ReplicaDocument {
        val dto = summary.toListDto()
        require(dto.toSummary(access.accountId, access.companyId) == summary)
        val id = summary.conversation.id
        return ReplicaDocument(
            id,
            checkpoint.revision,
            id,
            seal(checkpoint.generation, id, checkpoint.revision, id, Json.encodeToString(dto)),
        )
    }

    suspend fun admission(raw: ReplicaCheckpoint): CollaborationListAdmissionDto {
        require(
            raw.handle.scope == listScope(access) &&
                raw.handle.partition == listAdmissionPartition(access) &&
                raw.published &&
                raw.revision == 0L
        )
        return Json.decodeFromString<CollaborationListAdmissionDto>(
                open(raw.handle.generation, "admission", 0, "", raw.payload)
            )
            .also {
                require(it.revision == raw.handle.generation)
                it.toAdmission()
            }
    }

    suspend fun admission(value: CollaborationListAdmissionDto): String {
        value.toAdmission()
        return seal(value.revision, "admission", 0, "", Json.encodeToString(value))
    }

    private fun aad(generation: String, id: String, revision: Long, sort: String) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-list-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    generation,
                    id,
                    revision.toString(),
                    sort,
                )
            )
            .encodeToByteArray()

    private suspend fun seal(
        generation: String,
        id: String,
        revision: Long,
        sort: String,
        json: String,
    ): String {
        val bytes = json.encodeToByteArray()
        return try {
            require(bytes.size <= 4096)
            Base64.encode(cipher.encrypt(key, aad(generation, id, revision, sort), bytes))
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
        require(payload.length <= 6144)
        val bytes = cipher.decrypt(key, aad(generation, id, revision, sort), Base64.decode(payload))
        return try {
            require(bytes.size <= 4096)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }
}
