package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.sync.domain.entities.SyncCollection
import kotlin.time.Instant
import kotlinx.serialization.json.Json

fun leaveReplicaPartition(access: LeaveAccess) = "leave-replica/" + access.companyId

fun leaveReplicaScope(access: LeaveAccess) =
    ReplicaScope(access.sessionId, accountStorageKey(access.audience, access.accountId))

fun leaveReplicaId(collection: SyncCollection, id: String) =
    when (collection) {
        SyncCollection.LEAVE_REQUESTS -> "request/$id"
        SyncCollection.LEAVE_BALANCES -> "balance/$id"
        else -> error("Unsupported Leave collection.")
    }

fun LeaveRequestSummary.replicaSortKey(): String {
    val timestamp = submittedAt.toEpochMilliseconds()
    require(timestamp in 0..9_007_199_254_740_991L)
    return "r/" + (Long.MAX_VALUE - timestamp).toString().padStart(19, '0') + "/" + id
}

fun LeaveBalance.replicaSortKey() = "b/" + accountId

/** Authenticates visible ordering/index metadata along with the encrypted record. */
fun leaveReplicaAssociatedData(
    access: LeaveAccess,
    generation: String,
    id: String,
    version: Long,
    sortKey: String = "",
    tags: Set<String> = emptySet(),
): ByteArray =
    Json.encodeToString(
            listOf(
                "hris-leave-replica-v1",
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

fun LeaveCheckpoint.toReplicaDto() =
    LeaveCheckpointDto(
        access.permissions,
        access.sessionId,
        access.sessionExpiresAt.toString(),
        phase.name,
        cursor,
        refreshedAt?.toString(),
        refreshAfter.toString(),
        pollAt?.toString(),
    )

fun LeaveCheckpointDto.toCheckpoint(raw: ReplicaCheckpoint, access: LeaveAccess): LeaveCheckpoint {
    require(raw.handle.scope == leaveReplicaScope(access))
    require(raw.handle.partition == leaveReplicaPartition(access))
    require(syncSessionId.length in 1..128)
    require(permissions.size <= 256 && permissions.all { it.length in 1..128 })
    require(cursor == null || cursor.length in 1..8192)
    val stage = LeaveSyncPhase.valueOf(phase)
    require(stage != LeaveSyncPhase.CHANGES || cursor != null)
    require(!raw.published || stage == LeaveSyncPhase.CHANGES)
    val refreshed = refreshedAt?.let(Instant::parse)
    val refresh = Instant.parse(refreshAfter)
    require(refreshed == null || refreshed < refresh)
    require(!raw.published || refreshed != null)
    return LeaveCheckpoint(
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

fun LeaveDirectoryDto.toReplicaDirectory(access: LeaveAccess): LeaveDirectory {
    require(employments.items.size <= 200 && employments.nextCursor == null)
    val rows =
        if (employments.items.isEmpty()) listOf(emptyList()) else employments.items.chunked(20)
    val directory = copy(employments = LeavePageDto(rows.first(), null)).toDirectory(access, null)
    val items =
        rows.flatMap { copy(employments = LeavePageDto(it, null)).toDirectory(access, null).items }
    require(items.distinctBy { it.id }.size == items.size)
    require(items.zipWithNext().all { (first, next) -> first.employeeNumber < next.employeeNumber })
    return directory.copy(items = items.toList())
}
