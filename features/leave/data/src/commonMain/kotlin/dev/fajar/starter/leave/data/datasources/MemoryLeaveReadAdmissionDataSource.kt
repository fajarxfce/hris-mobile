package dev.fajar.starter.leave.data.datasources

import dev.fajar.starter.leave.data.dto.LeaveReadAdmissionDto
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

@Single
class MemoryLeaveReadAdmissionDataSource : LeaveReadAdmissionDataSource {
    private val records = MutableStateFlow<Map<String, LeaveReadAdmissionDto>>(emptyMap())

    override fun observe(key: String) = records.map { it[key] }.distinctUntilChanged()

    override fun current(key: String) = records.value[key]

    override fun write(key: String, value: LeaveReadAdmissionDto?) {
        records.update { if (value == null) it - key else it + (key to value) }
    }
}
