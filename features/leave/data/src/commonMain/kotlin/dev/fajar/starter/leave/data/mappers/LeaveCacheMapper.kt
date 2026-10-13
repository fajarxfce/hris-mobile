package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.data.dto.LeaveOverviewDto
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import kotlin.time.Instant
import kotlinx.serialization.json.Json

fun leavePartition(access: LeaveAccess) = "leave-overview/" + access.companyId

fun leaveAssociatedData(access: LeaveAccess, generation: String, purpose: String): ByteArray =
    Json.encodeToString(
            listOf(
                "hris-leave-overview-v1",
                access.sessionId,
                access.accountId,
                access.audience,
                access.companyId,
                generation,
                purpose,
            )
        )
        .encodeToByteArray()

fun LeaveOverview.toDto() =
    LeaveOverviewDto(
        directory.toDto(),
        selection.employeeId,
        requireNotNull(selection.year),
        selection.employmentAfter,
        selection.balanceAfter,
        selection.status?.name,
        selection.requestAfter,
        selection.employeeId?.let { balances.toBalancesDto(it, requireNotNull(selection.year)) },
        requests.toRequestsDto(),
        access.permissions,
        access.sessionExpiresAt.toString(),
        fetchedAt.toString(),
        refreshAfter.toString(),
    )

fun LeaveOverviewDto.toSnapshot(access: LeaveAccess, generation: String): LeaveOverview {
    require(permissions.size <= 256 && permissions.all { it.length in 1..128 })
    val owner =
        access.copy(
            permissions = permissions.toSet(),
            sessionExpiresAt = Instant.parse(sessionExpiresAt),
        )
    val selection =
        LeaveSelection(
            employeeId,
            year,
            employmentAfter,
            balanceAfter,
            status?.let(LeaveStatus::valueOf),
            requestAfter,
        )
    require(validateLeaveSelection(selection) is AppResult.Success)
    val directory = directory.toDirectory(owner, employmentAfter)
    require(
        if (employeeId == null) directory.items.isEmpty()
        else directory.items.any { it.id == employeeId }
    )
    require((employeeId == null) == (balances == null))
    if (employeeId == null) require(requests.items.isEmpty() && requests.nextCursor == null)
    val fetched = Instant.parse(fetchedAt)
    val expiry = Instant.parse(expiresAt)
    require(expiry > fetched && expiry <= fetched + LEAVE_FRESHNESS_PERIOD)
    return LeaveOverview(
        owner,
        generation,
        directory,
        selection,
        if (employeeId == null) LeavePage(emptyList())
        else requireNotNull(balances).toBalances(employeeId, year, balanceAfter),
        if (employeeId == null) LeavePage(emptyList())
        else requests.toRequests(employeeId, selection.status, requestAfter),
        fetched,
        expiry,
    )
}
