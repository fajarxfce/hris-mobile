package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseReceiptReferenceDto(
    val revisionId: String,
    val documentId: String?,
    val fileName: String?,
    val mediaType: String?,
    val size: Long?,
    val status: String?,
)
