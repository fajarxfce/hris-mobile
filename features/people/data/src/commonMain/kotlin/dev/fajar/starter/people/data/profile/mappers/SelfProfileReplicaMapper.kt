package dev.fajar.starter.people.data.profile.mappers

import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.people.data.profile.dto.SelfProfileCheckpointDto
import dev.fajar.starter.people.domain.profile.entities.*
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json

fun selfProfileReplicaScope(access: SelfProfileAccess) =
    ReplicaScope(access.sessionId, accountStorageKey(access.audience, access.accountId))

fun selfProfileReplicaPartition(access: SelfProfileAccess) =
    "self-profile-replica/" + access.companyId

fun selfProfileReplicaAssociatedData(
    access: SelfProfileAccess,
    generation: String,
    id: String,
    version: Long,
    sortKey: String = "",
    tags: Set<String> = emptySet(),
): ByteArray =
    Json.encodeToString(
            listOf(
                "hris-self-profile-replica-v1",
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

fun SelfProfileCheckpoint.toReplicaDto() =
    SelfProfileCheckpointDto(
        access.permissions,
        access.sessionId,
        phase.name,
        cursor,
        refreshedAt?.toString(),
        refreshAfter.toString(),
        pollAt?.toString(),
        access.timezone,
    )

fun SelfProfileCheckpointDto.toCheckpoint(
    raw: ReplicaCheckpoint,
    access: SelfProfileAccess,
): SelfProfileCheckpoint {
    require(
        raw.handle.scope == selfProfileReplicaScope(access) &&
            raw.handle.partition == selfProfileReplicaPartition(access)
    )
    require(schema == 1 && syncSessionId.length in 1..128)
    require(permissions.size <= 256 && permissions.all { it.length in 1..128 })
    require(cursor == null || cursor.length in 1..8192)
    TimeZone.of(timezone)
    val stage = SelfProfileSyncPhase.valueOf(phase)
    require(stage != SelfProfileSyncPhase.CHANGES || cursor != null)
    val refreshed = refreshedAt?.let(Instant::parse)
    val refresh = Instant.parse(refreshAfter)
    require(refreshed == null || (refreshed < refresh && stage == SelfProfileSyncPhase.CHANGES))
    require(!raw.published || refreshed != null)
    return SelfProfileCheckpoint(
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
