package dev.fajar.starter.expenses.data.replica.datasources

import dev.fajar.starter.expenses.data.replica.dto.ExpenseReadAdmissionDto
import kotlinx.coroutines.flow.Flow

/** Process-owned write buffer; the repository coordinates its encrypted durable counterpart. */
interface ExpenseReadAdmissionDataSource {
    fun observe(key: String): Flow<ExpenseReadAdmissionDto?>

    fun current(key: String): ExpenseReadAdmissionDto?

    fun write(key: String, value: ExpenseReadAdmissionDto?)
}
