package dev.fajar.starter.leave.presentation.navigation

import kotlinx.serialization.Serializable

@Serializable data class LeaveCreateRoute(val employeeId: String, val asOf: String)
