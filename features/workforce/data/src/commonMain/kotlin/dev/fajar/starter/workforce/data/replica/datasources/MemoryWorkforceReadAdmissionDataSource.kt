package dev.fajar.starter.workforce.data.replica.datasources

import dev.fajar.starter.workforce.data.replica.dto.WorkforceReadAdmissionDto
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

@Single
class MemoryWorkforceReadAdmissionDataSource : WorkforceReadAdmissionDataSource {
    private val records = MutableStateFlow<Map<String, WorkforceReadAdmissionDto>>(emptyMap())

    override fun observe(key: String) = records.map { it[key] }.distinctUntilChanged()

    override fun current(key: String) = records.value[key]

    override fun write(key: String, value: WorkforceReadAdmissionDto?) {
        records.update { if (value == null) it - key else it + (key to value) }
    }
}
