package dev.fajar.starter.leave.domain.entities

enum class LeavePortion(val halfDays: Int) {
    FULL(2),
    FIRST_HALF(1),
    SECOND_HALF(1),
}
