package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable data class OvertimeWithdrawalRequest(val expectedVersion: Long, val reason: String)
