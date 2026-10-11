package dev.fajar.starter.leave.domain.entities

data class LeaveSelection(
    val employeeId: String? = null,
    val year: Int? = null,
    val employmentAfter: String? = null,
    val balanceAfter: String? = null,
    val status: LeaveStatus? = null,
    val requestAfter: String? = null,
)
