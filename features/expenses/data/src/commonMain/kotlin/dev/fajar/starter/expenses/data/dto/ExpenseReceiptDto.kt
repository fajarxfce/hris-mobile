package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseReceiptDto(
    val revisionId: String,
    val fileName: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
    val possibleDuplicate: Boolean,
)
