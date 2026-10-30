package dev.fajar.starter.documents.data.metadata.datasources

import dev.fajar.starter.documents.data.metadata.dto.DocumentMetadataAdmissionDto
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

@Single
class MemoryDocumentMetadataAdmissionBuffer : DocumentMetadataAdmissionBuffer {
    private val records = MutableStateFlow<Map<String, DocumentMetadataAdmissionDto>>(emptyMap())

    override fun observe(key: String) = records.map { it[key] }.distinctUntilChanged()

    override fun current(key: String) = records.value[key]

    override fun write(key: String, value: DocumentMetadataAdmissionDto?) {
        records.update { if (value == null) it - key else it + (key to value) }
    }
}
