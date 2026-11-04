package dev.fajar.starter.collaboration.data.replica

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.collaborationId
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val SPACE_DIRECTORY_PREFIX = "collaboration-space-directory/"
internal const val SPACE_DIRECTORY_ADMISSION_PREFIX = "collaboration-space-directory-access/"

internal fun directoryScope(access: CollaborationSpaceLocalAccess): ReplicaScope {
    require(access.sessionId.length in 1..128 && access.sessionId.all { it.code in 33..126 })
    require(access.audience.length in 1..2048 && access.audience.all { it.code in 33..126 })
    collaborationId(access.accountId)
    collaborationId(access.spaceId)
    return ReplicaScope(access.sessionId, accountStorageKey(access.audience, access.accountId))
}

internal fun directoryPartition(access: CollaborationSpaceLocalAccess) =
    SPACE_DIRECTORY_PREFIX + collaborationId(access.spaceId)

internal fun directoryAdmissionPartition(access: CollaborationSpaceLocalAccess) =
    SPACE_DIRECTORY_ADMISSION_PREFIX + collaborationId(access.spaceId)

internal fun directoryAdmissionKey(access: CollaborationSpaceLocalAccess): String {
    directoryScope(access)
    return Json.encodeToString(listOf(access.audience, access.accountId, access.spaceId)).also {
        require(it.length <= 4096)
    }
}

@Serializable
private data class SpaceDirectoryCheckpointDto(
    val count: Int,
    val startedAt: String,
    val acquiredAt: String?,
    val nextCursor: String?,
    val complete: Boolean,
    val publishPartial: Boolean,
    val schema: Int = 1,
)

@Serializable
data class SpaceDirectoryAdmissionDto(val revision: String, val kind: String, val code: String?)

internal fun SpaceDirectoryAdmissionDto.toDirectoryAdmission():
    CollaborationSpaceDirectoryAdmission {
    require(code == null || (code.length in 1..128 && code.all { it.code in 33..126 }))
    return CollaborationSpaceDirectoryAdmission(
            collaborationId(revision),
            Failure(
                FailureKind.valueOf(kind),
                "Chat directory access requires verification.",
                code = code,
            ),
        )
        .also { require(validCollaborationSpaceDirectoryAdmission(it)) }
}

/** Authentication binds opaque indexes and every record to the original account/space namespace. */
internal class CollaborationSpaceDirectoryCodec(
    private val cipher: ContentCipher,
    private val key: ByteArray,
    private val access: CollaborationSpaceLocalAccess,
) {
    suspend fun checkpoint(raw: ReplicaCheckpoint): CollaborationSpaceDirectoryCheckpoint {
        require(
            raw.handle.scope == directoryScope(access) &&
                raw.handle.partition == directoryPartition(access)
        )
        collaborationId(raw.handle.generation)
        val value =
            Json.decodeFromString<SpaceDirectoryCheckpointDto>(
                open(raw.handle.generation, "checkpoint", raw.revision, "", raw.payload)
            )
        require(value.schema == 1)
        return CollaborationSpaceDirectoryCheckpoint(
                raw.handle.generation,
                raw.revision,
                value.count,
                Instant.parse(value.startedAt),
                value.acquiredAt?.let(Instant::parse),
                value.nextCursor,
                value.complete,
                raw.published,
                value.publishPartial,
            )
            .also { require(validCollaborationSpaceDirectoryCheckpoint(it)) }
    }

    suspend fun checkpoint(value: CollaborationSpaceDirectoryCheckpoint): String {
        require(validCollaborationSpaceDirectoryCheckpoint(value))
        return seal(
            value.generation,
            "checkpoint",
            value.revision,
            "",
            Json.encodeToString(
                SpaceDirectoryCheckpointDto(
                    value.count,
                    value.startedAt.toString(),
                    value.acquiredAt?.toString(),
                    value.nextCursor,
                    value.complete,
                    value.publishPartial,
                )
            ),
        )
    }

    suspend fun contact(
        checkpoint: CollaborationSpaceDirectoryCheckpoint,
        raw: ReplicaDocument,
    ): CollaborationSpaceContact {
        collaborationId(raw.id)
        require(
            raw.version in 1..checkpoint.revision && raw.sortKey == raw.id && raw.tags.isEmpty()
        )
        val value =
            Json.decodeFromString<CollaborationSpaceContactDto>(
                open(
                    checkpoint.generation,
                    raw.id,
                    raw.version,
                    raw.sortKey,
                    requireNotNull(raw.payload),
                )
            )
        require(value.companies.size in 1..8)
        return CollaborationSpaceContact(
                value.accountId,
                value.displayName,
                value.companies.map { CollaborationSpaceContactCompany(it.id, it.name) },
                value.hasMoreCompanies,
            )
            .also {
                require(
                    it.accountId == raw.id && validCollaborationSpaceContact(it, access.accountId)
                )
            }
    }

    suspend fun contact(
        checkpoint: CollaborationSpaceDirectoryCheckpoint,
        value: CollaborationSpaceContact,
    ): ReplicaDocument {
        require(validCollaborationSpaceContact(value, access.accountId) && checkpoint.revision > 0)
        val dto =
            CollaborationSpaceContactDto(
                value.accountId,
                value.displayName,
                value.companies.map { CollaborationSpaceContactCompanyDto(it.id, it.name) },
                value.hasMoreCompanies,
            )
        return ReplicaDocument(
            value.accountId,
            checkpoint.revision,
            value.accountId,
            seal(
                checkpoint.generation,
                value.accountId,
                checkpoint.revision,
                value.accountId,
                Json.encodeToString(dto),
            ),
        )
    }

    suspend fun admission(raw: ReplicaCheckpoint): SpaceDirectoryAdmissionDto {
        require(
            raw.handle.scope == directoryScope(access) &&
                raw.handle.partition == directoryAdmissionPartition(access) &&
                raw.published &&
                raw.revision == 0L
        )
        return Json.decodeFromString<SpaceDirectoryAdmissionDto>(
                open(raw.handle.generation, "admission", 0, "", raw.payload)
            )
            .also {
                require(it.revision == raw.handle.generation)
                it.toDirectoryAdmission()
            }
    }

    suspend fun admission(value: SpaceDirectoryAdmissionDto): String {
        value.toDirectoryAdmission()
        return seal(value.revision, "admission", 0, "", Json.encodeToString(value))
    }

    private fun aad(generation: String, id: String, revision: Long, sort: String) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-space-directory-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
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
        value: String,
    ): String {
        val bytes = value.encodeToByteArray()
        return try {
            require(bytes.size <= 16_384)
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
        value: String,
    ): String {
        require(value.length in 1..24_576)
        val bytes = cipher.decrypt(key, aad(generation, id, revision, sort), Base64.decode(value))
        return try {
            require(bytes.size <= 16_384)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }
}
