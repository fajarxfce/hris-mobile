@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.leave.data.dto.LeaveRequestPolicyDto
import dev.fajar.starter.leave.domain.entities.LeaveRequestPolicy
import kotlin.uuid.Uuid

fun LeaveRequestPolicyDto.toRequestPolicy(): LeaveRequestPolicy {
    Uuid.parse(typeId)
    require(Regex("[A-Z][A-Z0-9_-]{0,31}").matches(code))
    require(revision in 0..9_007_199_254_740_991L && name.isNotBlank() && name.length <= 200)
    return LeaveRequestPolicy(
        typeId,
        code,
        revision,
        name,
        paid,
        allowPartialDays,
        attachmentRequired,
    )
}
