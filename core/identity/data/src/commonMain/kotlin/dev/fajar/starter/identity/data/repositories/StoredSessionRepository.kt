package dev.fajar.starter.identity.data.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.AccountCacheStore
import dev.fajar.starter.identity.data.datasources.SessionDataSource
import dev.fajar.starter.identity.data.dto.SessionDto
import dev.fajar.starter.identity.data.mappers.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.securestorage.CredentialStore
import dev.fajar.starter.storage.safeStorageCall
import dev.fajar.starter.storage.safeStorageFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single

@Single
class StoredSessionRepository(
    private val credentials: CredentialStore,
    private val memory: SessionDataSource,
    private val accounts: AccountCacheStore,
) : SessionRepository {
    override val persistent
        get() = credentials.persistent

    private val localCommit = Mutex()
    private var loaded = false

    override fun observe(): Flow<AppResult<Session?>> = flow {
        when (val restored = current()) {
            is AppResult.Failed -> {
                emit(restored)
                return@flow
            }
            is AppResult.Success -> Unit
        }
        emitAll(safeStorageFlow(memory.record.map { it?.toSession() }))
    }

    override suspend fun current() = safeStorageCall {
        localCommit.withLock {
            if (!loaded) {
                val record = credentials.read()?.let { Json.decodeFromString<SessionDto>(it) }
                record?.toSession() // Validate before admitting the stored identity.
                accounts.activate(record?.id)
                memory.write(record)
                loaded = true
            }
            memory.record.value?.toSession()
        }
    }

    override suspend fun compareAndSet(expected: Session?, updated: Session?): AppResult<Boolean> {
        when (val restored = current()) {
            is AppResult.Failed -> return restored
            is AppResult.Success -> Unit
        }
        return safeStorageCall {
            localCommit.withLock {
                if (memory.record.value?.toSession() != expected) return@withLock false
                currentCoroutineContext().ensureActive()
                val record = updated?.toDto()
                // Local commit only: never hold this section across network I/O.
                // Once admitted, finish persistence/publication even if the caller is cancelled.
                withContext(NonCancellable) {
                    try {
                        accounts.activate(updated?.id)
                        credentials.write(record?.let { Json.encodeToString(it) })
                        memory.write(record)
                        loaded = true
                    } catch (error: Exception) {
                        // The vault remains authoritative; reconcile derivative cache on the next
                        // read.
                        loaded = false
                        throw error
                    }
                }
                true
            }
        }
    }
}
