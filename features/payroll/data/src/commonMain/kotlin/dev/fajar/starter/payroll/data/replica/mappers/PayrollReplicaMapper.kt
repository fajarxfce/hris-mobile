package dev.fajar.starter.payroll.data.replica.mappers

import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.payroll.data.replica.dto.PayrollCheckpointDto
import dev.fajar.starter.payroll.domain.entities.PayrollAccess
import dev.fajar.starter.payroll.domain.replica.entities.*
import kotlin.time.Instant
import kotlinx.serialization.json.Json

fun payrollReplicaScope(access: PayrollAccess) =
    ReplicaScope(access.sessionId, accountStorageKey(access.audience, access.accountId))

fun payrollReplicaPartition(access: PayrollAccess) = "payroll-replica/" + access.companyId

fun payrollReplicaAssociatedData(
    access: PayrollAccess,
    generation: String,
    id: String,
    version: Long,
    sortKey: String = "",
    tags: Set<String> = emptySet(),
): ByteArray =
    Json.encodeToString(
            listOf(
                "hris-payroll-replica-v1",
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

fun PayrollCheckpoint.toReplicaDto() =
    PayrollCheckpointDto(
        access.permissions,
        access.sessionId,
        access.sessionExpiresAt.toString(),
        phase.name,
        cursor,
        refreshedAt?.toString(),
        refreshAfter.toString(),
        pollAt?.toString(),
    )

fun PayrollCheckpointDto.toCheckpoint(
    raw: ReplicaCheckpoint,
    access: PayrollAccess,
): PayrollCheckpoint {
    require(
        raw.handle.scope == payrollReplicaScope(access) &&
            raw.handle.partition == payrollReplicaPartition(access)
    )
    require(schema == 1 && syncSessionId.length in 1..128)
    require(permissions.size <= 256 && permissions.all { it.length in 1..128 })
    require(cursor == null || cursor.length in 1..8192)
    val stage = PayrollSyncPhase.valueOf(phase)
    require(stage != PayrollSyncPhase.CHANGES || cursor != null)
    val refreshed = refreshedAt?.let(Instant::parse)
    val refresh = Instant.parse(refreshAfter)
    require(refreshed == null || (refreshed < refresh && stage == PayrollSyncPhase.CHANGES))
    require(!raw.published || refreshed != null)
    return PayrollCheckpoint(
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
