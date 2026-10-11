package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.data.dto.LeaveRequestSnapshotDto
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import kotlin.time.Instant
import kotlinx.serialization.json.Json

fun leaveRequestPartition(access: LeaveAccess) = "leave-request/" + access.companyId

fun leaveRequestAssociatedData(
    access: LeaveAccess,
    generation: String,
    purpose: String,
): ByteArray =
    Json.encodeToString(
            listOf(
                "hris-leave-request-v1",
                access.sessionId,
                access.accountId,
                access.audience,
                access.companyId,
                generation,
                purpose,
            )
        )
        .encodeToByteArray()

fun LeaveRequestSnapshot.toDto() =
    LeaveRequestSnapshotDto(
        request.toDto(),
        selection.historyAfter,
        access.permissions,
        access.sessionExpiresAt.toString(),
        fetchedAt.toString(),
        expiresAt.toString(),
    )

fun LeaveRequestSnapshotDto.toSnapshot(
    access: LeaveAccess,
    generation: String,
): LeaveRequestSnapshot {
    require(permissions.size <= 256 && permissions.all { it.length in 1..128 })
    val owner =
        access.copy(
            permissions = permissions.toSet(),
            sessionExpiresAt = Instant.parse(sessionExpiresAt),
        )
    val selection = LeaveRequestSelection(request.id, historyAfter)
    require(validateLeaveRequestSelection(selection) is AppResult.Success)
    val fetched = Instant.parse(fetchedAt)
    val expiry = Instant.parse(expiresAt)
    require(
        expiry > fetched &&
            expiry <= fetched + LEAVE_CACHE_PERIOD &&
            expiry <= owner.sessionExpiresAt
    )
    return LeaveRequestSnapshot(
        owner,
        generation,
        selection,
        request.toDetails(selection),
        fetched,
        expiry,
    )
}
