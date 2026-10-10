package dev.fajar.starter.transfers.data.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.storage.safeStorageCall
import dev.fajar.starter.transfers.data.datasources.TransferInputSource
import dev.fajar.starter.transfers.data.mappers.toEntity
import dev.fajar.starter.transfers.domain.repositories.TransferInputRepository
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

@Single
class SourceTransferInputRepository(private val source: TransferInputSource) :
    TransferInputRepository {
    override suspend fun metadata(sourceId: String) = safeStorageCall {
        source.metadata(sourceId).toEntity()
    }

    override fun read(sourceId: String): Flow<AppResult<ByteArray>> =
        flow { emitAll(source.read(sourceId)) }
            .map { bytes -> safeStorageCall { bytes } }
            .catch { error ->
                if (error !is Exception) throw error
                emit(safeStorageCall { throw error })
            }
}
