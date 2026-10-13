package dev.fajar.starter.leave.domain

import dev.fajar.starter.leave.domain.entities.*
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.*

fun LeaveRequestSummary.fixtureDetails(): LeaveRequestDetails {
    val dates =
        (0..(until.toEpochDays() - from.toEpochDays())).map { from.plus(it, DateTimeUnit.DAY) }
    return LeaveRequestDetails(
        id,
        employeeId,
        "E01",
        "Alex",
        "11111111-1111-1111-1111-111111111111",
        submittedAt,
        LeaveRequestPolicy(
            "55555555-5555-5555-5555-555555555555",
            typeCode,
            0,
            typeName,
            true,
            true,
            false,
        ),
        dates.mapIndexed { index, date ->
            val half = index == dates.lastIndex && charged.halfDays % 2 == 1
            val start = date.atStartOfDayIn(TimeZone.UTC)
            LeaveDay(
                date,
                if (half) LeavePortion.FIRST_HALF else LeavePortion.FULL,
                start,
                start + (if (half) 4 else 8).hours,
                480,
                if (half) 240 else 480,
            )
        },
        charged,
        "Family appointment",
        status,
        version,
        LeaveWorkflow(
            "88888888-8888-8888-8888-888888888888",
            if (status == LeaveStatus.APPROVED) LeaveWorkflowStatus.APPROVED
            else LeaveWorkflowStatus.PENDING,
            0,
            0,
        ),
        null,
        LeavePage(listOf(LeaveChange(version, LeaveChangeKind.SUBMITTED, status, submittedAt, ""))),
        if (status == LeaveStatus.PENDING) setOf(LeaveAction.WITHDRAW) else emptySet(),
        emptyList(),
    )
}

fun LeaveRequestDetails.fixtureSummary() =
    LeaveRequestSummary(
        id,
        employeeId,
        policy.code,
        policy.name,
        days.first().date,
        days.last().date,
        charged,
        status,
        submittedAt,
        version,
    )
