package dev.fajar.starter.inbox.data.datasources

import dev.fajar.starter.inbox.data.dto.InboxReadAdmissionDto
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

@Single
class MemoryInboxReadAdmissionDataSource : InboxReadAdmissionDataSource {
    private val records = MutableStateFlow<Map<String, InboxReadAdmissionDto>>(emptyMap())

    override fun observe(key: String) = records.map { it[key] }.distinctUntilChanged()

    override fun current(key: String) = records.value[key]

    override fun write(key: String, value: InboxReadAdmissionDto?) {
        records.update { if (value == null) it - key else it + (key to value) }
    }
}
