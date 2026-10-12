package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimeIntervalRequest(val startsAt: String, val endsAt: String, val breakMinutes: Int)
