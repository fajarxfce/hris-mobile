package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable data class LeavePageDto<T>(val items: List<T>, val nextCursor: String? = null)
