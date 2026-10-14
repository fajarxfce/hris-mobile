package dev.fajar.starter.payroll.data.replica.datasources

import dev.fajar.starter.payroll.data.replica.dto.PayrollReadAdmissionDto
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

@Single
class MemoryPayrollReadAdmissionDataSource : PayrollReadAdmissionDataSource {
    private val records = MutableStateFlow<Map<String, PayrollReadAdmissionDto>>(emptyMap())

    override fun observe(key: String) = records.map { it[key] }.distinctUntilChanged()

    override fun current(key: String) = records.value[key]

    override fun write(key: String, value: PayrollReadAdmissionDto?) {
        records.update { if (value == null) it - key else it + (key to value) }
    }
}
