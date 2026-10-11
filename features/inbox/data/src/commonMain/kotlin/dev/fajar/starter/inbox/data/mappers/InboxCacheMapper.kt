package dev.fajar.starter.inbox.data.mappers

import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.inbox.data.dto.InboxCheckpointDto
import dev.fajar.starter.inbox.domain.entities.*
import kotlin.time.Instant
import kotlinx.serialization.json.Json

fun inboxPartition(access: InboxAccess) = "inbox/" + access.companyId

/** Length-delimited JSON fields bind ciphertext without relying on ambiguous separators. */
fun inboxAssociatedData(
    access: InboxAccess,
    generation: String,
    purpose: String,
    id: String,
    version: Long,
): ByteArray =
    Json.encodeToString(
            listOf(
                "hris-inbox-v1",
                access.sessionId,
                access.accountId,
                access.audience,
                access.companyId,
                generation,
                purpose,
                id,
                version.toString(),
            )
        )
        .encodeToByteArray()

fun InboxCheckpoint.toDto() =
    InboxCheckpointDto(
        access.permissions,
        access.sessionExpiresAt.toString(),
        phase.name,
        cursor,
        refreshedAt?.toString(),
        expiresAt.toString(),
        pollAt?.toString(),
    )

fun InboxCheckpointDto.toCheckpoint(raw: ReplicaCheckpoint, access: InboxAccess): InboxCheckpoint {
    require(permissions.size <= 256 && permissions.all { it.length in 1..128 })
    require(cursor == null || cursor.length in 1..8192)
    val phase = InboxSyncPhase.valueOf(phase)
    require(phase != InboxSyncPhase.CHANGES || cursor != null)
    require(!raw.published || phase == InboxSyncPhase.CHANGES)
    val expiry = Instant.parse(expiresAt)
    val sessionExpiry = Instant.parse(sessionExpiresAt)
    val refreshed = refreshedAt?.let(Instant::parse)
    require(expiry <= sessionExpiry && (refreshed == null || refreshed < expiry))
    return InboxCheckpoint(
        access.copy(permissions = permissions.toSet(), sessionExpiresAt = sessionExpiry),
        raw.handle.generation,
        raw.revision,
        phase,
        cursor,
        raw.published,
        refreshed,
        expiry,
        pollAt?.let(Instant::parse),
    )
}
