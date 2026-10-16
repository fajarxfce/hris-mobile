package dev.fajar.starter.workforce.data.overtime.datasources

import dev.fajar.starter.workforce.data.overtime.dto.OvertimeReadAdmissionDto
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

@Single
class MemoryOvertimeReadAdmissionDataSource : OvertimeReadAdmissionDataSource {
    private val records = MutableStateFlow<Map<String, OvertimeReadAdmissionDto>>(emptyMap())

    override fun observe(key: String) = records.map { it[key] }.distinctUntilChanged()

    override fun current(key: String) = records.value[key]

    override fun write(key: String, value: OvertimeReadAdmissionDto?) {
        records.update { if (value == null) it - key else it + (key to value) }
    }
}
