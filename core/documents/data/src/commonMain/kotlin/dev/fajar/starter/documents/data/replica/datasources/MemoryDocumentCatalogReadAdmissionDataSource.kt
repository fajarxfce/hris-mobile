package dev.fajar.starter.documents.data.replica.datasources

import dev.fajar.starter.documents.data.replica.dto.DocumentCatalogReadAdmissionDto
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

@Single
class MemoryDocumentCatalogReadAdmissionDataSource : DocumentCatalogReadAdmissionDataSource {
    private val records = MutableStateFlow<Map<String, DocumentCatalogReadAdmissionDto>>(emptyMap())

    override fun observe(key: String) = records.map { it[key] }.distinctUntilChanged()

    override fun current(key: String) = records.value[key]

    override fun write(key: String, value: DocumentCatalogReadAdmissionDto?) {
        records.update { if (value == null) it - key else it + (key to value) }
    }
}
