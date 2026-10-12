@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.overtime.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.data.mappers.*
import dev.fajar.starter.workforce.data.overtime.dto.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.*

fun OvertimeSummary.toDto() =
    OvertimeSummaryDto(
        id,
        employeeId,
        workDate.toString(),
        timezone,
        requested.toDto(),
        actualMinutes,
        approvedMinutes,
        status.name,
        version,
    )

fun OvertimeSummaryDto.toSummary(): OvertimeSummary {
    listOf(id, employeeId).forEach { require(Uuid.parse(it).toString() == it) }
    val date = LocalDate.parse(workDate)
    val status = OvertimeStatus.valueOf(status)
    val interval = requested.toInterval()
    require(date.year in 2000..2100 && version in 0..999 && timezone.length in 1..100)
    TimeZone.of(timezone)
    require(actualMinutes == null || actualMinutes in 1..interval.workedMinutes)
    require(
        if (status == OvertimeStatus.PLANNED) actualMinutes == null && version == 0L
        else version > 0
    )
    if (status in setOf(OvertimeStatus.PENDING, OvertimeStatus.APPROVED, OvertimeStatus.REJECTED))
        require(actualMinutes != null)
    require(approvedMinutes == if (status == OvertimeStatus.APPROVED) actualMinutes else 0)
    return OvertimeSummary(
        id,
        employeeId,
        date,
        timezone,
        interval,
        actualMinutes,
        approvedMinutes,
        status,
        version,
    )
}

fun OvertimeOverview.toDto() =
    OvertimeOverviewDto(
        directory.toDto(),
        selection.employeeId,
        requireNotNull(selection.period).from.toString(),
        requireNotNull(selection.period).until.toString(),
        selection.status?.name,
        selection.employmentAfter,
        selection.requestAfter,
        OvertimePageDto(requests.items.map { it.toDto() }, requests.nextCursor),
    )

fun OvertimeOverviewDto.toOverview(access: WorkforceAccess): OvertimeOverview {
    val selection =
        OvertimeSelection(
            employeeId,
            CalendarPeriod(LocalDate.parse(from), LocalDate.parse(until)),
            status?.let(OvertimeStatus::valueOf),
            employmentAfter,
            requestAfter,
        )
    require(validateOvertimeSelection(selection) is AppResult.Success)
    val directory = directory.toDirectory(access, employmentAfter)
    require(
        if (employeeId == null) directory.items.isEmpty()
        else directory.items.any { it.id == employeeId }
    )
    val requests =
        if (employeeId == null) {
            require(requests.items.isEmpty() && requests.nextCursor == null)
            OvertimePage<OvertimeSummary>(emptyList())
        } else
            validateOvertimePage(
                requests.items.map { it.toSummary() },
                requests.nextCursor,
                employeeId,
                requireNotNull(selection.period),
                selection.status,
                requestAfter,
            )
    return OvertimeOverview(directory, selection, requests)
}

fun OvertimeRequestView.toDto() =
    OvertimeRequestCacheDto(
        selection.requestId,
        selection.employeeId,
        selection.employmentAfter,
        selection.historyAfter,
        details.request.toDto(),
        details.approval?.toDto(),
        OvertimePageDto(details.history.items.map { it.toDto() }, details.history.nextCursor),
        details.actions.map { it.name },
    )

fun OvertimeRequestCacheDto.toView(): OvertimeRequestView {
    val selection = OvertimeRequestSelection(requestId, employeeId, employmentAfter, historyAfter)
    require(validateOvertimeRequestSelection(selection) is AppResult.Success)
    return OvertimeRequestView(
        selection,
        mapOvertimeDetails(request, approval?.toApproval(), history, actions, selection),
    )
}

fun <T, D> OvertimeSnapshot<T>.toDto(mapValue: (T) -> D) =
    OvertimeSnapshotDto(
        access.permissions.toSet(),
        access.sessionExpiresAt.toString(),
        fetchedAt.toString(),
        expiresAt.toString(),
        mapValue(value),
    )

fun <T, D> OvertimeSnapshotDto<D>.toSnapshot(
    access: WorkforceAccess,
    generation: String,
    mapValue: (D, WorkforceAccess) -> T,
): OvertimeSnapshot<T> {
    require(permissions.size <= 256 && permissions.all { it.length in 1..128 })
    val owner =
        access.copy(
            permissions = permissions.toSet(),
            sessionExpiresAt = Instant.parse(sessionExpiresAt),
        )
    val fetched = Instant.parse(fetchedAt)
    val expiry = Instant.parse(expiresAt)
    require(
        expiry > fetched &&
            expiry <= fetched + OVERTIME_CACHE_PERIOD &&
            expiry <= owner.sessionExpiresAt
    )
    return OvertimeSnapshot(owner, generation, mapValue(value, owner), fetched, expiry)
}
