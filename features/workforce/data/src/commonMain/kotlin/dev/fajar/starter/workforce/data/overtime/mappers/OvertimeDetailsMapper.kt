@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.overtime.mappers

import dev.fajar.starter.workforce.data.overtime.dto.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun OvertimeWorkflowDto.toApproval(): OvertimeApproval {
    require(stages.size in 1..8)
    stages.forEach { stage ->
        require(stage.size <= 200 && stage.distinct().size == stage.size)
        stage.forEach { require(Uuid.parse(it).toString() == it) }
    }
    return OvertimeApprovalDto(id, status, currentStep, stages.size, version).toApproval()
}

fun OvertimeApprovalDto.toApproval(): OvertimeApproval {
    require(Uuid.parse(id).toString() == id)
    require(
        stageCount in 1..8 &&
            currentStep in 0 until stageCount &&
            version in 0..9_007_199_254_740_991L
    )
    return OvertimeApproval(
        id,
        OvertimeApprovalStatus.valueOf(status),
        currentStep,
        stageCount,
        version,
    )
}

fun OvertimeDetailsDto.toDetails(selection: OvertimeRequestSelection): OvertimeDetails =
    mapOvertimeDetails(request, approval?.toApproval(), history, actions, selection)

fun mapOvertimeDetails(
    requestDto: OvertimeRequestDto,
    approval: OvertimeApproval?,
    history: OvertimePageDto<OvertimeChangeDto>,
    actions: List<String>,
    selection: OvertimeRequestSelection,
): OvertimeDetails {
    val request = requestDto.toRequest()
    require(request.id == selection.requestId && request.employeeId == selection.employeeId)
    require(approval?.id == request.approvalId)
    if (approval != null) {
        require(
            if (request.status == OvertimeStatus.PENDING)
                approval.status in
                    setOf(OvertimeApprovalStatus.PENDING, OvertimeApprovalStatus.BLOCKED)
            else
                approval.status.name ==
                    if (request.status == OvertimeStatus.WITHDRAWN) "CANCELLED"
                    else request.status.name
        )
    }
    require(actions.size <= 4 && actions.distinct().size == actions.size)
    val mappedActions = actions.map(OvertimeAction::valueOf).toSet()
    mappedActions.forEach { action ->
        require(
            when (action) {
                OvertimeAction.SUBMIT_ACTUAL -> request.status == OvertimeStatus.PLANNED
                OvertimeAction.WITHDRAW ->
                    request.status in setOf(OvertimeStatus.PLANNED, OvertimeStatus.PENDING)
                OvertimeAction.APPROVE,
                OvertimeAction.REJECT -> request.status == OvertimeStatus.PENDING
            }
        )
    }
    return OvertimeDetails(
        request,
        approval,
        history.toHistory(request, selection.historyAfter),
        mappedActions,
    )
}

fun OvertimePageDto<OvertimeChangeDto>.toHistory(
    request: OvertimeRequest,
    after: Long?,
): OvertimePage<OvertimeChange> {
    require(items.size <= 10)
    var previous = after ?: Long.MAX_VALUE
    val mapped =
        items.map { row ->
            require(
                row.revision in 0..request.version &&
                    row.revision < previous &&
                    row.reason.length <= 1_000 &&
                    row.recordedAt.length <= 40
            )
            val kind = OvertimeChangeKind.valueOf(row.kind)
            val status = OvertimeStatus.valueOf(row.status)
            val actual = row.actual?.toInterval()
            listOfNotNull(row.approvalId, row.actorId).forEach {
                require(Uuid.parse(it).toString() == it)
            }
            require((kind == OvertimeChangeKind.PLANNED) == (row.revision == 0L))
            require(
                when (kind) {
                    OvertimeChangeKind.PLANNED -> status == OvertimeStatus.PLANNED && actual == null
                    OvertimeChangeKind.ACTUAL_SUBMITTED ->
                        status == OvertimeStatus.PENDING && row.revision == 1L
                    OvertimeChangeKind.DECIDED ->
                        status in
                            setOf(
                                OvertimeStatus.PENDING,
                                OvertimeStatus.APPROVED,
                                OvertimeStatus.REJECTED,
                            ) && row.revision >= 2
                    OvertimeChangeKind.WITHDRAWN -> status == OvertimeStatus.WITHDRAWN
                }
            )
            require((actual == null) == (row.approvalId == null))
            if (
                status in
                    setOf(OvertimeStatus.PENDING, OvertimeStatus.APPROVED, OvertimeStatus.REJECTED)
            )
                require(actual != null)
            require(
                row.approvedMinutes ==
                    if (status == OvertimeStatus.APPROVED) actual?.workedMinutes else 0
            )
            if (kind != OvertimeChangeKind.DECIDED) require(row.reason.isNotBlank())
            if (actual != null)
                require(actual == request.actual && row.approvalId == request.approvalId)
            previous = row.revision
            OvertimeChange(
                row.revision,
                kind,
                status,
                actual,
                row.approvedMinutes,
                row.approvalId,
                row.actorId,
                Instant.parse(row.recordedAt),
                row.reason,
            )
        }
    if (after == null) {
        val latest = requireNotNull(mapped.firstOrNull())
        require(
            latest.revision == request.version &&
                latest.status == request.status &&
                latest.approvedMinutes == request.approvedMinutes &&
                latest.approvalId == request.approvalId
        )
    }
    require(
        nextCursor == null ||
            (mapped.size == 10 &&
                nextCursor == mapped.last().revision.toString() &&
                nextCursor != "0")
    )
    return OvertimePage(mapped.toList(), nextCursor)
}

fun OvertimeChange.toDto() =
    OvertimeChangeDto(
        revision,
        kind.name,
        status.name,
        actual?.toDto(),
        approvedMinutes,
        approvalId,
        actorId,
        recordedAt.toString(),
        reason,
    )

fun OvertimeApproval.toDto() =
    OvertimeApprovalDto(id, status.name, currentStep, stageCount, version)
