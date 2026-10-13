package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable data class ExpenseActionRequest(val expectedVersion: Long, val reason: String)
