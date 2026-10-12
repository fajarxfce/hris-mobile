package dev.fajar.starter.documents.data.repositories

import dev.fajar.starter.documents.domain.entities.DocumentFileDeclaration
import dev.fajar.starter.documents.domain.repositories.DocumentOutputRepository
import dev.fajar.starter.fileio.FileOutputSource
import dev.fajar.starter.fileio.safeFileWriteCall
import org.koin.core.annotation.Single

@Single
class DeviceDocumentOutputRepository(private val outputs: FileOutputSource) :
    DocumentOutputRepository {
    override suspend fun select(requestId: String, declaration: DocumentFileDeclaration) =
        safeFileWriteCall {
            outputs.select(requestId, declaration.name, declaration.mediaType, declaration.size)
        }

    override suspend fun write(requestId: String, offset: Long, bytes: ByteArray) =
        safeFileWriteCall {
            outputs.write(requestId, offset, bytes)
        }

    override suspend fun complete(requestId: String) = safeFileWriteCall {
        outputs.complete(requestId)
    }

    override suspend fun release(requestId: String) = safeFileWriteCall {
        outputs.release(requestId)
    }
}
