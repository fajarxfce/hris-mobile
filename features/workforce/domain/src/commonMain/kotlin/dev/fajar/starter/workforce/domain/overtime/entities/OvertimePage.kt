package dev.fajar.starter.workforce.domain.overtime.entities

data class OvertimePage<T>(val items: List<T>, val nextCursor: String? = null)
