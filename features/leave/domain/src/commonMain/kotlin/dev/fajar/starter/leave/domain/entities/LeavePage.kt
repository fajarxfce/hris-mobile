package dev.fajar.starter.leave.domain.entities

/** A bounded server page; a cursor is not a locally computed offset. */
data class LeavePage<T>(val items: List<T>, val nextCursor: String? = null)
