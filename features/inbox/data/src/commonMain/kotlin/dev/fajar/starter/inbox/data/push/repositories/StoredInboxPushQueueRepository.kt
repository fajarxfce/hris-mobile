package dev.fajar.starter.inbox.data.push.repositories

import dev.fajar.starter.inbox.data.push.dto.InboxPushQueueDto
import dev.fajar.starter.inbox.data.push.mappers.*
import dev.fajar.starter.inbox.domain.push.entities.InboxPushQueue
import dev.fajar.starter.inbox.domain.push.repositories.InboxPushQueueRepository
import dev.fajar.starter.securestorage.CredentialStore
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

/** One application container owns this encrypted installation record. */
@Single
class StoredInboxPushQueueRepository(
    @Named("inbox-push-queue") private val credentials: CredentialStore
) : InboxPushQueueRepository {
    private val commits = Mutex()

    override suspend fun read() = safeStorageCall { commits.withLock { readCurrent() } }

    override suspend fun compareAndSet(expected: InboxPushQueue, updated: InboxPushQueue) =
        safeStorageCall {
            commits.withLock {
                if (readCurrent() != expected) return@withLock false
                val dto = updated.toDto()
                require(dto.toQueue() == updated)
                val encoded = Json.encodeToString(dto)
                require(encoded.encodeToByteArray().size <= 131_072)
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) { credentials.write(encoded) }
                true
            }
        }

    private suspend fun readCurrent(): InboxPushQueue {
        val encoded = credentials.read() ?: return InboxPushQueue()
        require(encoded.length <= 131_072 && encoded.encodeToByteArray().size <= 131_072)
        return Json.decodeFromString<InboxPushQueueDto>(encoded).toQueue()
    }
}
