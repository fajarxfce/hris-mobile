package dev.fajar.starter.documents.data.replica.mappers

import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.dto.DocumentEmploymentDto
import dev.fajar.starter.documents.data.mappers.toEmployment
import dev.fajar.starter.documents.data.replica.dto.DocumentCatalogCheckpointDto
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.replica.entities.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import kotlin.time.Instant
import kotlinx.serialization.json.Json

fun documentCatalogReplicaScope(access: DocumentAccess) =
    ReplicaScope(access.sessionId, accountStorageKey(access.audience, access.accountId))

fun documentCatalogReplicaPartition(access: DocumentAccess) = "document-catalog/" + access.companyId

fun documentCatalogReplicaAssociatedData(
    access: DocumentAccess,
    generation: String,
    id: String,
    version: Long,
    sortKey: String = "",
    tags: Set<String> = emptySet(),
): ByteArray =
    Json.encodeToString(
            listOf(
                "hris-document-catalog-v1",
                access.audience,
                access.accountId,
                access.companyId,
                generation,
                id,
                version.toString(),
                sortKey,
            ) + tags.sorted()
        )
        .encodeToByteArray()

fun DocumentCatalogCheckpoint.toReplicaDto() =
    DocumentCatalogCheckpointDto(
        access.permissions,
        access.sessionId,
        access.sessionExpiresAt.toString(),
        access.authorizationExpiresAt.toString(),
        access.authorizationExpiryCode,
        phase.name,
        cursor,
        refreshedAt?.toString(),
        refreshAfter.toString(),
        pollAt?.toString(),
        employments.map {
            DocumentEmploymentDto(it.id, it.employeeNumber, it.status.name, it.version)
        },
        recordSchema = recordSchema,
        metadataSupported = metadataSupported,
    )

fun DocumentCatalogCheckpointDto.toCheckpoint(
    raw: ReplicaCheckpoint,
    access: DocumentAccess,
): DocumentCatalogCheckpoint {
    require(
        raw.handle.scope == documentCatalogReplicaScope(access) &&
            raw.handle.partition == documentCatalogReplicaPartition(access)
    )
    require(schema == 1 && syncSessionId.length in 1..128)
    require(recordSchema in 1..DocumentCatalogCheckpoint.CURRENT_RECORD_SCHEMA)
    require(recordSchema >= 2 || !metadataSupported)
    require(permissions.size <= 256 && permissions.all { it.length in 1..128 })
    require(cursor == null || cursor.length in 1..8192)
    require(authorizationExpiryCode.length in 1..128)
    require(employments.size <= 200)
    val directory = employments.map { it.toEmployment() }
    require(
        directory.distinctBy { it.id }.size == directory.size &&
            directory.distinctBy { it.employeeNumber }.size == directory.size
    )
    val stage = DocumentCatalogSyncPhase.valueOf(phase)
    require(stage != DocumentCatalogSyncPhase.CHANGES || cursor != null)
    val refreshed = refreshedAt?.let(Instant::parse)
    val refresh = Instant.parse(refreshAfter)
    require(refreshed == null || (refreshed < refresh && stage == DocumentCatalogSyncPhase.CHANGES))
    require(!raw.published || refreshed != null)
    return DocumentCatalogCheckpoint(
        access.copy(
            sessionId = syncSessionId,
            permissions = permissions.toSet(),
            sessionExpiresAt = Instant.parse(sessionExpiresAt),
            authorizationExpiresAt = Instant.parse(authorizationExpiresAt),
            authorizationExpiryCode = authorizationExpiryCode,
        ),
        raw.handle.generation,
        raw.revision,
        stage,
        cursor,
        raw.published,
        refreshed,
        refresh,
        pollAt?.let(Instant::parse),
        directory.toList(),
        recordSchema,
        metadataSupported,
    )
}
