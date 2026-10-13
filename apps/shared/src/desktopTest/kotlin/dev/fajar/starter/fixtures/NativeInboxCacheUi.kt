package dev.fajar.starter.fixtures

import dev.fajar.starter.common.result.*
import dev.fajar.starter.inbox.domain.entities.InboxAccess
import dev.fajar.starter.inbox.domain.repositories.InboxCacheRepository
import java.util.concurrent.atomic.AtomicInteger

/** Test-only fault injection; ciphertext and ordinary reads use the real repository. */
class NativeInboxCacheUi(private val actual: InboxCacheRepository) :
    InboxCacheRepository by actual {
    @Volatile var rejectClear = false
    val clearAttempts = AtomicInteger()

    override suspend fun clear(access: InboxAccess): AppResult<Boolean> {
        clearAttempts.incrementAndGet()
        return if (rejectClear)
            AppResult.Failed(
                Failure(FailureKind.Storage, "Storage unavailable", code = "client.storage")
            )
        else actual.clear(access)
    }
}
