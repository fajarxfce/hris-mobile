@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.identity.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.data.push.repositories.StoredNativePushStateRepository
import dev.fajar.starter.identity.domain.push.entities.*
import dev.fajar.starter.securestorage.MemoryCredentialStore
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class NativePushStateTest {
    private val owner =
        NativePushOwner(
            "local",
            "https://hris.invalid/api/v1/",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            Instant.parse("2027-01-01T00:00:00Z"),
        )
    private val pending =
        NativePushState(
            desired = true,
            owner = owner,
            token = "secret-token-value",
            pending =
                NativePushCommand.Save(
                    "33333333-3333-4333-8333-333333333333",
                    null,
                    NativePushPlatform.ANDROID,
                    "secret-token-value",
                ),
        )

    @Test
    fun observationPublishesCommittedFactsAndReopensAfterCancellation() = runTest {
        val credentials = MemoryCredentialStore()
        val repository = StoredNativePushStateRepository(credentials)
        val observed = mutableListOf<AppResult<NativePushState>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observe().toList(observed)
            }
        runCurrent()
        assertEquals(AppResult.Success(NativePushState()), observed.single())
        repository.compareAndSet(NativePushState(), pending)
        runCurrent()
        assertEquals(AppResult.Success(pending), observed.last())
        repository.compareAndSet(NativePushState(), pending.copy(desired = false))
        runCurrent()
        assertEquals(2, observed.size)
        reader.cancelAndJoin()
        val disabled = pending.copy(desired = false)
        repository.compareAndSet(pending, disabled)
        runCurrent()
        assertEquals(2, observed.size)
        assertEquals(
            AppResult.Success(disabled),
            StoredNativePushStateRepository(credentials).observe().first(),
        )
    }

    @Test
    fun restartRetainsTheFrozenCommandAndACompetingWriteCannotReplaceIt() = runTest {
        val credentials = MemoryCredentialStore()
        val repository = StoredNativePushStateRepository(credentials)
        assertEquals(AppResult.Success(true), repository.compareAndSet(NativePushState(), pending))
        assertEquals(
            AppResult.Success(false),
            repository.compareAndSet(NativePushState(), pending.copy(desired = false)),
        )
        val restored = StoredNativePushStateRepository(credentials)
        assertEquals(AppResult.Success(pending), restored.read())
        assertFalse(pending.toString().contains("secret"))
        assertFalse(pending.pending.toString().contains("secret"))
        assertEquals(
            AppResult.Success(true),
            restored.compareAndSet(
                pending,
                pending.copy(desired = false, pending = null, retiringToken = true),
            ),
        )
    }

    @Test
    fun corruptStateCannotBecomeAnEmptyInstallationOrOverwriteItsPendingToken() = runTest {
        val credentials = MemoryCredentialStore()
        val repository = StoredNativePushStateRepository(credentials)
        for (body in listOf("not json", " ".repeat(32769))) {
            credentials.write(body)
            assertIs<AppResult.Failed>(repository.read())
            assertIs<AppResult.Failed>(repository.compareAndSet(NativePushState(), pending))
            assertEquals(body, credentials.read())
        }
    }

    @Test
    fun invalidOwnershipTokenMismatchAndExcessiveRetirementsNeverCommit() = runTest {
        val credentials = MemoryCredentialStore()
        val repository = StoredNativePushStateRepository(credentials)
        for (state in
            listOf(
                pending.copy(owner = null),
                pending.copy(token = "another-token-value"),
                pending.copy(retiringToken = true),
                pending.copy(owner = owner.copy(familyId = "invalid")),
                pending.copy(revocations = List(33) { owner }),
                pending.copy(revocations = listOf(owner, owner)),
            )) {
            assertIs<AppResult.Failed>(repository.compareAndSet(NativePushState(), state))
            assertNull(credentials.read())
        }
    }

    @Test
    fun concurrentPreparationsRetainOnlyOneCommand() = runTest {
        val repository = StoredNativePushStateRepository(MemoryCredentialStore())
        val writes =
            listOf(pending, pending.copy(desired = false))
                .map { state -> async { repository.compareAndSet(NativePushState(), state) } }
                .awaitAll()
        assertEquals(1, writes.count { it == AppResult.Success(true) })
        assertEquals(1, writes.count { it == AppResult.Success(false) })
    }
}
