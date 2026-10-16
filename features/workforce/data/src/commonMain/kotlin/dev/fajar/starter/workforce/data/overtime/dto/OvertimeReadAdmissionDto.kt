package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimeReadAdmissionDto(val revision: String, val kind: String, val code: String?)
