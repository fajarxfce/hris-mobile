package dev.fajar.starter.documents.data.metadata.datasources

import dev.fajar.starter.documents.data.metadata.dto.DocumentMetadataAdmissionDto
import kotlinx.coroutines.flow.Flow

/** Process-owned write buffer keeps a known denial visible while durable storage is pending. */
interface DocumentMetadataAdmissionBuffer {
    fun observe(key: String): Flow<DocumentMetadataAdmissionDto?>

    fun current(key: String): DocumentMetadataAdmissionDto?

    fun write(key: String, value: DocumentMetadataAdmissionDto?)
}
