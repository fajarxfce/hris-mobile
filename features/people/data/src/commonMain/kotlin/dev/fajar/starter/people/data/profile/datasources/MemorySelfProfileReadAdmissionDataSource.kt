package dev.fajar.starter.people.data.profile.datasources

import dev.fajar.starter.people.data.profile.dto.SelfProfileReadAdmissionDto
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

@Single
class MemorySelfProfileReadAdmissionDataSource : SelfProfileReadAdmissionDataSource {
    private val records = MutableStateFlow<Map<String, SelfProfileReadAdmissionDto>>(emptyMap())

    override fun observe(key: String) = records.map { it[key] }.distinctUntilChanged()

    override fun current(key: String) = records.value[key]

    override fun write(key: String, value: SelfProfileReadAdmissionDto?) {
        records.update { if (value == null) it - key else it + (key to value) }
    }
}
