package dev.fajar.starter.expenses.data.dto

data class ExpenseReceiptHeadersDto(
    val entityTags: List<String>,
    val lengths: List<String>,
    val mediaTypes: List<String>,
    val encodings: List<String>,
    val ranges: List<String>,
)
