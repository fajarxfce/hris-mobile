package dev.fajar.starter.workforce.data.replica.mappers

import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.workforce.data.replica.dto.WorkforceCheckpointDto
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.replica.entities.*
import kotlin.time.Instant
import kotlinx.serialization.json.Json

fun workforceReplicaScope(access: WorkforceAccess) =
    ReplicaScope(access.sessionId, accountStorageKey(access.audience, access.accountId))

fun workforceReplicaPartition(access: WorkforceAccess) = "workforce-replica/" + access.companyId

fun workforceReplicaAssociatedData(
    access: WorkforceAccess,
    generation: String,
    id: String,
    version: Long,
    sortKey: String = "",
    tags: Set<String> = emptySet(),
): ByteArray =
    Json.encodeToString(
            listOf(
                "hris-workforce-replica-v1",
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

fun WorkforceCheckpoint.toReplicaDto() =
    WorkforceCheckpointDto(
        access.permissions,
        access.sessionId,
        access.sessionExpiresAt.toString(),
        phase.name,
        cursor,
        refreshedAt?.toString(),
        refreshAfter.toString(),
        pollAt?.toString(),
    )

fun WorkforceCheckpointDto.toCheckpoint(
    raw: ReplicaCheckpoint,
    access: WorkforceAccess,
): WorkforceCheckpoint {
    require(
        raw.handle.scope == workforceReplicaScope(access) &&
            raw.handle.partition == workforceReplicaPartition(access)
    )
    require(schema == 1 && syncSessionId.length in 1..128)
    require(permissions.size <= 256 && permissions.all { it.length in 1..128 })
    require(cursor == null || cursor.length in 1..8192)
    val stage = WorkforceSyncPhase.valueOf(phase)
    require(stage != WorkforceSyncPhase.CHANGES || cursor != null)
    val refreshed = refreshedAt?.let(Instant::parse)
    val refresh = Instant.parse(refreshAfter)
    require(refreshed == null || (refreshed < refresh && stage == WorkforceSyncPhase.CHANGES))
    return WorkforceCheckpoint(
        access.copy(
            sessionId = syncSessionId,
            permissions = permissions.toSet(),
            sessionExpiresAt = Instant.parse(sessionExpiresAt),
        ),
        raw.handle.generation,
        raw.revision,
        stage,
        cursor,
        raw.published,
        refreshed,
        refresh,
        pollAt?.let(Instant::parse),
    )
}
