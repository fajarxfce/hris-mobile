package dev.fajar.starter.documents.data.replica.datasources

import dev.fajar.starter.documents.data.replica.dto.DocumentCatalogReadAdmissionDto
import kotlinx.coroutines.flow.Flow

/** Process-owned write buffer; the repository coordinates its encrypted durable counterpart. */
interface DocumentCatalogReadAdmissionDataSource {
    fun observe(key: String): Flow<DocumentCatalogReadAdmissionDto?>

    fun current(key: String): DocumentCatalogReadAdmissionDto?

    fun write(key: String, value: DocumentCatalogReadAdmissionDto?)
}
