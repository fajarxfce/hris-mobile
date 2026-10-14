package dev.fajar.starter.people.data.replica.mappers

import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.people.data.replica.dto.DirectoryCheckpointDto
import dev.fajar.starter.people.domain.replica.entities.*
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json

fun directoryReplicaScope(access: DirectoryAccess) =
    ReplicaScope(access.sessionId, accountStorageKey(access.audience, access.accountId))

fun directoryReplicaPartition(access: DirectoryAccess) = "directory-replica/" + access.companyId

fun directoryReplicaAssociatedData(
    access: DirectoryAccess,
    generation: String,
    id: String,
    version: Long,
    sortKey: String = "",
    tags: Set<String> = emptySet(),
): ByteArray =
    Json.encodeToString(
            listOf(
                "hris-directory-replica-v1",
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

fun DirectoryCheckpoint.toReplicaDto() =
    DirectoryCheckpointDto(
        access.permissions,
        access.sessionId,
        phase.name,
        cursor,
        refreshedAt?.toString(),
        refreshAfter.toString(),
        pollAt?.toString(),
        access.timezone,
    )

fun DirectoryCheckpointDto.toCheckpoint(
    raw: ReplicaCheckpoint,
    access: DirectoryAccess,
): DirectoryCheckpoint {
    require(
        raw.handle.scope == directoryReplicaScope(access) &&
            raw.handle.partition == directoryReplicaPartition(access)
    )
    require(schema == 1 && syncSessionId.length in 1..128)
    require(permissions.size <= 256 && permissions.all { it.length in 1..128 })
    require(cursor == null || cursor.length in 1..8192)
    TimeZone.of(timezone)
    val stage = DirectorySyncPhase.valueOf(phase)
    require(stage != DirectorySyncPhase.CHANGES || cursor != null)
    val refreshed = refreshedAt?.let(Instant::parse)
    val refresh = Instant.parse(refreshAfter)
    require(refreshed == null || (refreshed < refresh && stage == DirectorySyncPhase.CHANGES))
    require(!raw.published || refreshed != null)
    return DirectoryCheckpoint(
        access.copy(
            sessionId = syncSessionId,
            permissions = permissions.toSet(),
            timezone = timezone,
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
