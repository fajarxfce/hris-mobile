package dev.fajar.starter.workforce.presentation.navigation

import kotlinx.serialization.Serializable

@Serializable data object OvertimeRoute

@Serializable
data class OvertimeRequestRoute(
    val id: String,
    val employeeId: String,
    val employmentAfter: String? = null,
)
