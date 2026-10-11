package dev.fajar.starter.people.presentation.directory

data class EmployeeRowState(
    val id: String,
    val name: String,
    val position: String?,
    val metadata: String,
    val initials: String,
)
