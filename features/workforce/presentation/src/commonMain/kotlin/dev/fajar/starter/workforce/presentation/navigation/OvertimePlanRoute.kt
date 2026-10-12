package dev.fajar.starter.workforce.presentation.navigation

import kotlinx.serialization.Serializable

@Serializable
data class OvertimePlanRoute(
    val employeeId: String,
    val workDate: String,
    val employmentAfter: String? = null,
)
