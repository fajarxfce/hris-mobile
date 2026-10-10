package dev.fajar.starter.transfers.data.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import dev.fajar.starter.storage.safeStorageCall
import dev.fajar.starter.transfers.data.mappers.*
import dev.fajar.starter.transfers.domain.entities.Transfer
import dev.fajar.starter.transfers.domain.repositories.TransferQueueRepository
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

@Single
class StoredTransferQueueRepository(private val store: TransferStore) : TransferQueueRepository {
    override fun observe(sessionId: String): Flow<AppResult<List<Transfer>>> =
        flow { emitAll(store.observe(sessionId)) }
            .map { rows -> safeStorageCall { rows.map { it.toEntity() } } }
            .catch { error ->
                if (error !is Exception) throw error
                emit(safeStorageCall { throw error })
            }

    override suspend fun get(sessionId: String, id: String) = safeStorageCall {
        store.get(sessionId, id)?.toEntity()
    }

    override suspend fun create(
        sessionId: String,
        transfer: Transfer,
        maxBytes: Long,
        maxItems: Int,
    ) = safeStorageCall { store.create(sessionId, transfer.toRecord(), maxBytes, maxItems) }

    override suspend fun commit(
        sessionId: String,
        transfer: Transfer,
        chunk: ByteArray?,
        clearContent: Boolean,
    ) = safeStorageCall {
        require(transfer.offset in 0..transfer.file.size)
        val updated =
            transfer.copy(
                storedBytes = if (clearContent) 0 else transfer.storedBytes + (chunk?.size ?: 0)
            )
        require(updated.storedBytes in 0..transfer.file.size)
        val applied =
            store.replace(
                sessionId,
                transfer.checkpointVersion,
                updated.toRecord(),
                chunk?.let { TransferChunkRecord(transfer.storedBytes, it) },
                clearContent,
            )
        if (applied) updated.copy(checkpointVersion = transfer.checkpointVersion + 1) else null
    }

    override suspend fun read(sessionId: String, id: String, offset: Long) = safeStorageCall {
        val block = requireNotNull(store.chunk(sessionId, id, offset))
        val start = offset - block.offset
        require(start >= 0 && start < block.bytes.size)
        block.bytes.copyOfRange(start.toInt(), block.bytes.size)
    }

    override suspend fun remove(sessionId: String, id: String, version: Long) = safeStorageCall {
        store.remove(sessionId, id, version)
    }
}
