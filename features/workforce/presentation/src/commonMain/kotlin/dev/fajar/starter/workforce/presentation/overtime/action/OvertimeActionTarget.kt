package dev.fajar.starter.workforce.presentation.overtime.action

import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeAction
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeRequestSelection
import kotlinx.serialization.Serializable

data class OvertimeActionTarget(
    val workspaceId: String,
    val selection: OvertimeRequestSelection,
    val kind: OvertimeActionKind,
)

@Serializable
enum class OvertimeActionKind(val action: OvertimeAction) {
    ACTUAL(OvertimeAction.SUBMIT_ACTUAL),
    WITHDRAW(OvertimeAction.WITHDRAW),
}
