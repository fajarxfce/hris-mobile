package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveReadAdmissionDto(val revision: String, val kind: String, val code: String?)
