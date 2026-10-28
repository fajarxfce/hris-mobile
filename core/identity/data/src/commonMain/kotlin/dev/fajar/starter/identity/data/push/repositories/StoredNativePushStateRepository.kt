package dev.fajar.starter.identity.data.push.repositories

import dev.fajar.starter.identity.data.push.dto.NativePushStateDto
import dev.fajar.starter.identity.data.push.mappers.*
import dev.fajar.starter.identity.domain.push.entities.NativePushState
import dev.fajar.starter.identity.domain.push.repositories.NativePushStateRepository
import dev.fajar.starter.securestorage.CredentialStore
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

/** One application container owns this named secure record and its local commit guard. */
@Single
class StoredNativePushStateRepository(
    @Named("native-push-state") private val credentials: CredentialStore
) : NativePushStateRepository {
    private val commits = Mutex()
    private val changes = MutableStateFlow(0L)
    override val persistent
        get() = credentials.persistent

    override suspend fun read() = safeStorageCall { commits.withLock { readCurrent() } }

    override fun observe() = changes.map { read() }.distinctUntilChanged()

    override suspend fun compareAndSet(expected: NativePushState, updated: NativePushState) =
        safeStorageCall {
            commits.withLock {
                if (readCurrent() != expected) return@withLock false
                val dto = updated.toDto()
                require(dto.toState() == updated)
                val encoded = Json.encodeToString(dto)
                require(encoded.encodeToByteArray().size <= 32_768)
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    credentials.write(encoded)
                    changes.value++
                }
                true
            }
        }

    private suspend fun readCurrent(): NativePushState {
        val encoded = credentials.read() ?: return NativePushState()
        require(encoded.encodeToByteArray().size <= 32_768)
        return Json.decodeFromString<NativePushStateDto>(encoded).toState()
    }
}
