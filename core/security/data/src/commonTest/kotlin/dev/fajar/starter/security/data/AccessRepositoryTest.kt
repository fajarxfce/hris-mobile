package dev.fajar.starter.security.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.security.data.access.datasources.*
import dev.fajar.starter.security.data.access.dto.AccessResponse
import dev.fajar.starter.security.data.access.repositories.CachedAccessRepository
import dev.fajar.starter.security.domain.access.entities.Permission
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class AccessRepositoryTest {
    @Test
    fun cacheStorageIsBoundedAndEvictionNeverReassignsGrants() {
        val cache = MemoryAccessCache()
        repeat(100) { cache.write("session-$it", response("files.download")) }
        assertNull(cache.read("session-0"))
        assertNull(cache.read("session-91"))
        assertNotNull(cache.read("session-92"))
        assertNotNull(cache.read("session-99"))
        cache.write("session-92", response("activity.save"))
        cache.write("next", response("files.upload"))
        assertNull(cache.read("session-93"))
        assertEquals(listOf("activity.save"), cache.read("session-92")?.permissions)
        assertEquals(listOf("files.upload"), cache.read("next")?.permissions)
    }

    @Test
    fun lateResponseCannotOverwriteAnotherSessionsGrants() = runTest {
        val pending = CompletableDeferred<AccessResponse>()
        val cache = MemoryAccessCache()
        val source =
            object : AccessRemoteDataSource {
                override suspend fun fetch(sessionId: String) =
                    if (sessionId == "old") pending.await() else response("files.download")
            }
        val repository = CachedAccessRepository(source, cache)
        val old = async { repository.refresh("old") }
        repository.refresh("new")
        pending.complete(response("activity.save"))
        old.await()
        assertEquals(
            setOf(Permission.DownloadFile),
            assertIs<AppResult.Success<*>>(repository.cached("new"))
                .value
                .let { it as dev.fajar.starter.security.domain.access.entities.AccessSnapshot }
                .permissions,
        )
        assertEquals(listOf("files.download"), cache.read("new")?.permissions)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationAndInvalidPayloadsDoNotPublishGrants() = runTest {
        val pending = CompletableDeferred<AccessResponse>()
        val cache = MemoryAccessCache()
        val source =
            object : AccessRemoteDataSource {
                override suspend fun fetch(sessionId: String) =
                    withContext(NonCancellable) { pending.await() }
            }
        val repository = CachedAccessRepository(source, cache)
        val job = launch { repository.refresh("one") }
        runCurrent()
        job.cancel()
        pending.complete(response("activity.save"))
        job.join()
        assertNull(cache.read("one"))
        val invalid =
            CachedAccessRepository(
                object : AccessRemoteDataSource {
                    override suspend fun fetch(sessionId: String) =
                        response("activity.save").copy(expiresAtEpochMillis = -1)
                },
                cache,
            )
        assertIs<AppResult.Failed>(invalid.refresh("one"))
        assertNull(cache.read("one"))
    }

    private fun response(permission: String) =
        AccessResponse(listOf("member"), listOf(permission), Long.MAX_VALUE)
}
