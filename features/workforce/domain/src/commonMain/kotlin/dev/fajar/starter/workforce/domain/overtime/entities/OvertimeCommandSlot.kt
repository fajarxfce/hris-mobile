package dev.fajar.starter.workforce.domain.overtime.entities

/** A creation intent belongs to its employment; subsequent actions share the request's slot. */
sealed interface OvertimeCommandSlot {
    data class Plan(val employeeId: String) : OvertimeCommandSlot

    data class Request(val requestId: String) : OvertimeCommandSlot
}
