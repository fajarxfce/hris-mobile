package dev.fajar.starter.expenses.data.replica.mappers

import dev.fajar.starter.database.*
import dev.fajar.starter.expenses.data.replica.dto.ExpenseCheckpointDto
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.expenses.domain.replica.entities.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import kotlin.time.Instant
import kotlinx.serialization.json.Json

fun expenseReplicaScope(access: ExpenseAccess) =
    ReplicaScope(access.sessionId, accountStorageKey(access.audience, access.accountId))

fun expenseReplicaPartition(access: ExpenseAccess) = "expense-replica/" + access.companyId

fun expenseReplicaAssociatedData(
    access: ExpenseAccess,
    generation: String,
    id: String,
    version: Long,
    sortKey: String = "",
    tags: Set<String> = emptySet(),
): ByteArray =
    Json.encodeToString(
            listOf(
                "hris-expense-replica-v1",
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

fun ExpenseCheckpoint.toReplicaDto() =
    ExpenseCheckpointDto(
        access.permissions,
        access.sessionId,
        access.sessionExpiresAt.toString(),
        access.timezone,
        phase.name,
        cursor,
        refreshedAt?.toString(),
        refreshAfter.toString(),
        pollAt?.toString(),
    )

fun ExpenseCheckpointDto.toCheckpoint(
    raw: ReplicaCheckpoint,
    access: ExpenseAccess,
): ExpenseCheckpoint {
    require(
        raw.handle.scope == expenseReplicaScope(access) &&
            raw.handle.partition == expenseReplicaPartition(access)
    )
    require(schema == 1 && syncSessionId.length in 1..128)
    require(permissions.size <= 256 && permissions.all { it.length in 1..128 })
    require(cursor == null || cursor.length in 1..8192)
    require(timezone.length in 1..100)
    kotlinx.datetime.TimeZone.of(timezone)
    val stage = ExpenseSyncPhase.valueOf(phase)
    require(stage != ExpenseSyncPhase.CHANGES || cursor != null)
    val refreshed = refreshedAt?.let(Instant::parse)
    val refresh = Instant.parse(refreshAfter)
    require(refreshed == null || (refreshed < refresh && stage == ExpenseSyncPhase.CHANGES))
    return ExpenseCheckpoint(
        access.copy(
            sessionId = syncSessionId,
            timezone = timezone,
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
