package dev.fajar.starter.leave.domain.entities

/** Exact half-day units; never binary floating point. */
data class LeaveDays(val halfDays: Int) {
    init {
        require(halfDays >= 0)
    }
}
