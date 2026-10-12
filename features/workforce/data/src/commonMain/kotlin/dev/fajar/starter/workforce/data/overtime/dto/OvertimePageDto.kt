package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable data class OvertimePageDto<T>(val items: List<T>, val nextCursor: String? = null)
