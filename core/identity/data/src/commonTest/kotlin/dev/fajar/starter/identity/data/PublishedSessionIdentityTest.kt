@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.identity.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.AccountCacheStore
import dev.fajar.starter.identity.data.datasources.MemorySessionDataSource
import dev.fajar.starter.identity.data.mappers.toDto
import dev.fajar.starter.identity.data.repositories.PublishedSessionIdentityRepository
import dev.fajar.starter.identity.data.repositories.StoredSessionRepository
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.usecases.ObservePublishedSessionIdentity
import dev.fajar.starter.securestorage.CredentialStore
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json

class PublishedSessionIdentityTest {
    private val original =
        Session(
            "session",
            User("one", "Example", "example@example.invalid"),
            SessionTokens("access", "refresh", 100),
            SessionBinding(
                "11111111-1111-4111-8111-111111111111",
                "https://example.invalid/",
                1_000,
            ),
        )

    @Test
    fun observingDoesNotAcquireStorageAndOnlyCompletedSessionChangesArePublished() = runTest {
        val memory = MemorySessionDataSource()
        var reads = 0
        var activations = 0
        var stored: String? = Json.encodeToString(original.toDto())
        val restored = CompletableDeferred<Unit>()
        val sessions =
            StoredSessionRepository(
                object : CredentialStore {
                    override val persistent = true

                    override suspend fun read(): String? {
                        reads++
                        return stored
                    }

                    override suspend fun write(value: String?) {
                        stored = value
                    }
                },
                memory,
                object : AccountCacheStore {
                    override suspend fun activate(sessionId: String?, accountKey: String?) {
                        activations++
                        restored.await()
                    }
                },
            )
        val observe = ObservePublishedSessionIdentity(PublishedSessionIdentityRepository(memory))
        val values = mutableListOf<AppResult<SessionIdentity?>>()
        val reader = launch(UnconfinedTestDispatcher(testScheduler)) { observe().toList(values) }
        try {
            assertEquals(listOf<AppResult<SessionIdentity?>>(AppResult.Success(null)), values)
            assertEquals(0, reads)
            assertEquals(0, activations)
            val restore = async { sessions.current() }
            runCurrent()
            assertEquals(1, reads)
            assertEquals(1, activations)
            assertEquals(listOf<AppResult<SessionIdentity?>>(AppResult.Success(null)), values)
            restored.complete(Unit)
            assertEquals(AppResult.Success(original), restore.await())
            assertEquals(
                AppResult.Success(SessionIdentity(original.id, original.user)),
                values.last(),
            )
            val count = values.size
            val rotated = original.copy(tokens = SessionTokens("next", "next", 200))
            assertEquals(AppResult.Success(true), sessions.compareAndSet(original, rotated))
            assertEquals(count, values.size)
            val next =
                rotated.copy(
                    id = "replacement",
                    user = User("two", "Second", "two@example.invalid"),
                )
            assertEquals(AppResult.Success(true), sessions.compareAndSet(rotated, next))
            assertEquals(AppResult.Success(SessionIdentity(next.id, next.user)), values.last())
            assertEquals(AppResult.Success(true), sessions.clear(next.id))
            assertEquals(AppResult.Success(null), values.last())
            reader.cancelAndJoin()
            val afterClose = values.toList()
            assertEquals(AppResult.Success(true), sessions.compareAndSet(null, original))
            assertEquals(afterClose, values)
        } finally {
            restored.complete(Unit)
            reader.cancelAndJoin()
        }
    }

    @Test
    fun malformedPublishedIdentityFailsWithoutBecomingASignOut() = runTest {
        val memory = MemorySessionDataSource()
        memory.write(original.toDto().copy(id = ""))
        val values = mutableListOf<AppResult<SessionIdentity?>>()
        val reader =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                ObservePublishedSessionIdentity(PublishedSessionIdentityRepository(memory))()
                    .toList(values)
            }
        try {
            assertEquals(1, values.size)
            assertEquals(
                FailureKind.Storage,
                assertIs<AppResult.Failed>(values.single()).failure.kind,
            )
            memory.write(original.toDto())
            assertEquals(2, values.size)
            assertEquals(
                AppResult.Success(SessionIdentity(original.id, original.user)),
                values.last(),
            )
        } finally {
            reader.cancelAndJoin()
        }
    }
}
