package dev.fajar.starter.workforce.data.overtime.mappers

import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.workforce.data.dto.*
import dev.fajar.starter.workforce.data.mappers.*
import dev.fajar.starter.workforce.data.overtime.dto.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlin.time.Instant
import kotlinx.serialization.json.Json

fun overtimeReplicaScope(access: WorkforceAccess) =
    ReplicaScope(access.sessionId, accountStorageKey(access.audience, access.accountId))

fun overtimeReplicaPartition(access: WorkforceAccess) = "overtime-replica/" + access.companyId

fun overtimeWriteCheckpoint(raw: ReplicaCheckpoint?): String? =
    raw?.let { Json.encodeToString(listOf(it.handle.generation, it.revision.toString())) }

fun OvertimeSummary.replicaSortKey() = "r/" + workDate.toString() + "/" + id

fun overtimeReplicaAssociatedData(
    access: WorkforceAccess,
    generation: String,
    id: String,
    version: Long,
    sortKey: String = "",
    tags: Set<String> = emptySet(),
): ByteArray =
    Json.encodeToString(
            listOf(
                "hris-overtime-replica-v1",
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

fun OvertimeCheckpoint.toReplicaDto() =
    OvertimeCheckpointDto(
        access.permissions,
        access.sessionId,
        access.sessionExpiresAt.toString(),
        phase.name,
        cursor,
        refreshedAt?.toString(),
        refreshAfter.toString(),
        pollAt?.toString(),
    )

fun OvertimeCheckpointDto.toCheckpoint(
    raw: ReplicaCheckpoint,
    access: WorkforceAccess,
): OvertimeCheckpoint {
    require(
        raw.handle.scope == overtimeReplicaScope(access) &&
            raw.handle.partition == overtimeReplicaPartition(access)
    )
    require(schema == 1 && syncSessionId.length in 1..128)
    require(permissions.size <= 256 && permissions.all { it.length in 1..128 })
    require(cursor == null || cursor.length in 1..8192)
    val stage = OvertimeSyncPhase.valueOf(phase)
    require(stage != OvertimeSyncPhase.CHANGES || cursor != null)
    val refreshed = refreshedAt?.let(Instant::parse)
    val refresh = Instant.parse(refreshAfter)
    require(refreshed == null || (refreshed < refresh && stage == OvertimeSyncPhase.CHANGES))
    return OvertimeCheckpoint(
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

fun OvertimeHistoryPage.toDto() =
    OvertimeHistoryPageDto(
        requestId,
        requestVersion,
        after,
        OvertimePageDto(history.items.map { it.toDto() }, history.nextCursor),
    )

/** A matching older activity page may fill a migration gap, without replacing current facts. */
fun OvertimeSnapshot<OvertimeRequestView>.toLegacyOvertimeHistory(
    selection: OvertimeRequestSelection,
    currentVersion: Long,
): OvertimeHistoryPageDto? {
    if (
        value.selection.requestId != selection.requestId ||
            value.selection.employeeId != selection.employeeId ||
            value.selection.historyAfter != selection.historyAfter ||
            value.details.request.version > currentVersion ||
            selection.historyAfter == null
    )
        return null
    return OvertimeHistoryPageDto(
        selection.requestId,
        value.details.request.version,
        requireNotNull(selection.historyAfter),
        OvertimePageDto(
            value.details.history.items.map { it.toDto() },
            value.details.history.nextCursor,
        ),
    )
}
