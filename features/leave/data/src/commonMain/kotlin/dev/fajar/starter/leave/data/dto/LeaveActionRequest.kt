package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable data class LeaveActionRequest(val version: Long, val reason: String)
