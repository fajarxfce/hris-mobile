package dev.fajar.starter.workforce.presentation.navigation

import dev.fajar.starter.workforce.presentation.overtime.action.OvertimeActionKind
import kotlinx.serialization.Serializable

@Serializable
data class OvertimeActionRoute(
    val id: String,
    val employeeId: String,
    val kind: OvertimeActionKind,
    val employmentAfter: String? = null,
)
