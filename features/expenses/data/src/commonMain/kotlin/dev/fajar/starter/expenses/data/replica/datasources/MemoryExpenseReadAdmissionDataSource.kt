package dev.fajar.starter.expenses.data.replica.datasources

import dev.fajar.starter.expenses.data.replica.dto.ExpenseReadAdmissionDto
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

@Single
class MemoryExpenseReadAdmissionDataSource : ExpenseReadAdmissionDataSource {
    private val records = MutableStateFlow<Map<String, ExpenseReadAdmissionDto>>(emptyMap())

    override fun observe(key: String) = records.map { it[key] }.distinctUntilChanged()

    override fun current(key: String) = records.value[key]

    override fun write(key: String, value: ExpenseReadAdmissionDto?) {
        records.update { if (value == null) it - key else it + (key to value) }
    }
}
