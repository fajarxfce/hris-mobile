package dev.fajar.starter.people.data.replica.datasources

import dev.fajar.starter.people.data.replica.dto.DirectoryReadAdmissionDto
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

@Single
class MemoryDirectoryReadAdmissionDataSource : DirectoryReadAdmissionDataSource {
    private val records = MutableStateFlow<Map<String, DirectoryReadAdmissionDto>>(emptyMap())

    override fun observe(key: String) = records.map { it[key] }.distinctUntilChanged()

    override fun current(key: String) = records.value[key]

    override fun write(key: String, value: DirectoryReadAdmissionDto?) {
        records.update { if (value == null) it - key else it + (key to value) }
    }
}
