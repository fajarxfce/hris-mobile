@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.domain.entities.*
import kotlin.uuid.Uuid

fun LeaveRequestDto.toReplicaRequest(
    expectedId: String,
    historyAfter: Long? = null,
): LeaveReplicaRecord.Request {
    val details = toDetails(LeaveRequestSelection(expectedId, historyAfter))
    return LeaveReplicaRecord.Request(details)
}

fun LeaveRequestDetails.toSummary() =
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

fun LeaveAccountDto.toReplicaBalance(expectedId: String): LeaveReplicaRecord.Balance {
    require(id == expectedId)
    listOf(id, employeeId, typeId).forEach { require(Uuid.parse(it).toString() == it) }
    require(year in 1900..2200 && version in 0..9_007_199_254_740_991L)
    require(Regex("[A-Z][A-Z0-9_-]{0,31}").matches(typeCode))
    require(typeName.isNotBlank() && typeName.length <= 200)
    return LeaveReplicaRecord.Balance(
        employeeId,
        LeaveBalance(
            id,
            typeId,
            typeCode,
            typeName,
            year,
            availableDays.toLeaveDays(),
            reservedDays.toLeaveDays(),
            consumedDays.toLeaveDays(),
            closed,
            version,
        ),
    )
}

fun LeaveReplicaRecord.Balance.toDto() =
    LeaveAccountDto(
        value.accountId,
        employeeId,
        value.typeId,
        value.year,
        value.available.toWireDays(),
        value.reserved.toWireDays(),
        value.consumed.toWireDays(),
        value.version,
        value.closed,
        value.typeCode,
        value.typeName,
    )

fun LeaveRequestSummary.toDto() =
    LeaveRequestSummaryDto(
        id,
        employeeId,
        typeCode,
        typeName,
        from.toString(),
        until.toString(),
        charged.toWireDays(),
        status.name,
        submittedAt.toString(),
        version,
    )

fun LeaveRequestSummaryDto.toReplicaSummary(expectedId: String): LeaveRequestSummary {
    require(id == expectedId)
    val page = LeavePageDto(listOf(this), null).toRequests(employeeId, null, null)
    return page.items.single()
}
