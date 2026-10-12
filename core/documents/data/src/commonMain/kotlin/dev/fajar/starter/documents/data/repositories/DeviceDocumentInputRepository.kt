package dev.fajar.starter.documents.data.repositories

import dev.fajar.starter.documents.domain.entities.DocumentFileSelection
import dev.fajar.starter.documents.domain.repositories.DocumentInputRepository
import dev.fajar.starter.fileio.FileInputSource
import dev.fajar.starter.fileio.safeFileCall
import org.koin.core.annotation.Single

@Single
class DeviceDocumentInputRepository(private val inputs: FileInputSource) : DocumentInputRepository {
    override suspend fun select(requestId: String, mediaTypes: Set<String>) = safeFileCall {
        inputs.select(requestId, mediaTypes)?.let {
            DocumentFileSelection(it.name, it.mediaType, it.size)
        }
    }

    override suspend fun release(requestId: String) = safeFileCall { inputs.release(requestId) }
}
