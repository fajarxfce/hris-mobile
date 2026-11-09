package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.StoredCollaborationRecoveryWakeRepository
import dev.fajar.starter.collaboration.data.repositories.StoredCollaborationSpaceRecoveryWakeRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceRecoveryWakeStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun reopenRetainsTheOriginalGenerationAndLateAcknowledgmentCannotEraseItsSuccessor() =
        withDatabase { f ->
            val first = value(f.wakes().request())
            f.reopen()
            assertEquals(AppResult.Success(first), f.wakes().pending())
            assertEquals(AppResult.Success(true), f.wakes().acknowledge(first))
            assertEquals(AppResult.Success(null), f.wakes().pending())
            val next = value(f.wakes().request())
            assertNotEquals(first, next)
            assertEquals(AppResult.Success(false), f.wakes().acknowledge(first))
            f.reopen()
            assertEquals(AppResult.Success(next), f.wakes().pending())
            assertNull(f.keys.read())
        }

    @Test
    fun companyAndOrganizationWakesRemainIndependentEvenWithIdenticalGenerations() =
        withDatabase { f ->
            val generation = historyId(7100)
            for (key in
                listOf(
                    CollaborationRecoveryWake.WORK_KEY,
                    CollaborationSpaceRecoveryWake.WORK_KEY,
                )) {
                assertNotNull(f.database.workWakes.request(key, generation, 32))
            }
            val company = StoredCollaborationRecoveryWakeRepository(f.database.workWakes)
            val group = f.wakes()
            assertEquals(
                AppResult.Success(true),
                company.acknowledge(CollaborationRecoveryWake(generation)),
            )
            assertEquals(
                AppResult.Success(CollaborationSpaceRecoveryWake(generation)),
                group.pending(),
            )
            val nextCompany = value(company.request())
            assertEquals(
                AppResult.Success(true),
                group.acknowledge(CollaborationSpaceRecoveryWake(generation)),
            )
            assertEquals(AppResult.Success(nextCompany), company.pending())
            f.reopen()
            assertEquals(AppResult.Success(null), f.wakes().pending())
            assertEquals(
                AppResult.Success(nextCompany),
                StoredCollaborationRecoveryWakeRepository(f.database.workWakes).pending(),
            )
        }

    @Test
    fun concurrentRequestsHaveUniqueGenerationsAndOnlyTheCurrentOneCanBeAcknowledged() =
        withDatabase { f ->
            val requests = coroutineScope {
                (1..16).map { async { value(f.wakes().request()) } }.awaitAll()
            }
            assertEquals(16, requests.toSet().size)
            val current = assertNotNull(value(f.wakes().pending()))
            assertTrue(current in requests)
            for (old in requests.filter { it != current }) assertEquals(
                AppResult.Success(false),
                f.wakes().acknowledge(old),
            )
            assertEquals(AppResult.Success(current), f.wakes().pending())
            assertEquals(AppResult.Success(true), f.wakes().acknowledge(current))
            assertEquals(AppResult.Success(false), f.wakes().acknowledge(current))
        }

    @Test
    fun cancellationAfterRealRequestCommitPreservesThePendingGeneration() = withDatabase { f ->
        var committed: WorkWakeRecord? = null
        val store =
            object : WorkWakeStore by f.database.workWakes {
                override suspend fun request(
                    key: String,
                    generation: String,
                    maxKeys: Int,
                ): WorkWakeRecord? {
                    committed = f.database.workWakes.request(key, generation, maxKeys)
                    throw CancellationException("Fictional lost process after commit")
                }
            }
        assertFailsWith<CancellationException> { f.wakes(store).request() }
        val original = assertNotNull(committed)
        f.reopen()
        assertEquals(
            AppResult.Success(CollaborationSpaceRecoveryWake(original.generation)),
            f.wakes().pending(),
        )
    }

    @Test
    fun fullKeyCapacityRejectsNewOrganizationWorkWithoutChangingOtherKeys() = withDatabase { f ->
        for (i in 1..32) assertNotNull(
            f.database.workWakes.request("fixture.$i", historyId(7100 + i), 32)
        )
        var attempts = 0
        val store =
            object : WorkWakeStore by f.database.workWakes {
                override suspend fun request(
                    key: String,
                    generation: String,
                    maxKeys: Int,
                ): WorkWakeRecord? {
                    attempts++
                    return f.database.workWakes.request(key, generation, maxKeys)
                }
            }
        val failure = assertIs<AppResult.Failed>(f.wakes(store).request()).failure
        assertEquals(FailureKind.Conflict, failure.kind)
        assertEquals("client.collaboration_space_recovery_capacity", failure.code)
        assertEquals(1, attempts)
        assertEquals(AppResult.Success(null), f.wakes().pending())
        for (i in 1..32) assertEquals(
            historyId(7100 + i),
            assertNotNull(f.database.workWakes.get("fixture.$i")).generation,
        )
    }

    @Test
    fun anExistingOrganizationKeyStillAdvancesAtCapacity() = withDatabase { f ->
        val first = value(f.wakes().request())
        for (i in 1..31) assertNotNull(
            f.database.workWakes.request("fixture.$i", historyId(7100 + i), 32)
        )
        val next = value(f.wakes().request())
        assertNotEquals(first, next)
        assertEquals(AppResult.Success(false), f.wakes().acknowledge(first))
        f.reopen()
        assertEquals(AppResult.Success(next), f.wakes().pending())
    }

    @Test
    fun malformedOrForeignReadbackCannotBecomeAnOrganizationWake() = withDatabase { f ->
        val current = value(f.wakes().request())
        for (raw in
            listOf(
                WorkWakeRecord(CollaborationRecoveryWake.WORK_KEY, current.generation, true),
                WorkWakeRecord(CollaborationSpaceRecoveryWake.WORK_KEY, "malformed", true),
                WorkWakeRecord(
                    CollaborationSpaceRecoveryWake.WORK_KEY,
                    "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
                    false,
                ),
            )) {
            val store =
                object : WorkWakeStore by f.database.workWakes {
                    override suspend fun get(key: String) = raw
                }
            assertEquals(
                FailureKind.Storage,
                assertIs<AppResult.Failed>(f.wakes(store).pending()).failure.kind,
            )
        }
        assertEquals(
            FailureKind.Storage,
            assertIs<AppResult.Failed>(
                    f.wakes().acknowledge(CollaborationSpaceRecoveryWake("malformed"))
                )
                .failure
                .kind,
        )
        assertEquals(AppResult.Success(current), f.wakes().pending())
    }

    @Test
    fun cancellationAfterActualAcknowledgmentIsAnIdempotentCompletedWakeOnReopen() =
        withDatabase { f ->
            val first = value(f.wakes().request())
            val store =
                object : WorkWakeStore by f.database.workWakes {
                    override suspend fun acknowledge(key: String, generation: String): Boolean {
                        check(f.database.workWakes.acknowledge(key, generation))
                        throw CancellationException("Fictional lost acknowledgment response")
                    }
                }
            assertFailsWith<CancellationException> { f.wakes(store).acknowledge(first) }
            f.reopen()
            assertEquals(AppResult.Success(null), f.wakes().pending())
            assertEquals(AppResult.Success(false), f.wakes().acknowledge(first))
        }

    @Test
    fun aNewWakeWinsWhileAnOldAcknowledgmentIsWaitingToWrite() = withDatabase { f ->
        val first = value(f.wakes().request())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store =
            object : WorkWakeStore by f.database.workWakes {
                override suspend fun acknowledge(key: String, generation: String): Boolean {
                    entered.complete(Unit)
                    release.await()
                    return f.database.workWakes.acknowledge(key, generation)
                }
            }
        coroutineScope {
            val old = async { f.wakes(store).acknowledge(first) }
            try {
                entered.await()
                val newer = value(f.wakes().request())
                release.complete(Unit)
                assertEquals(AppResult.Success(false), old.await())
                assertEquals(AppResult.Success(newer), f.wakes().pending())
            } finally {
                release.complete(Unit)
                old.cancelAndJoin()
            }
        }
    }

    private fun CollaborationHistoryFixture.wakes(store: WorkWakeStore = database.workWakes) =
        StoredCollaborationSpaceRecoveryWakeRepository(store)

    private fun <T> value(result: AppResult<T>): T = assertIs<AppResult.Success<T>>(result).value

    private fun withDatabase(block: suspend (CollaborationHistoryFixture) -> Unit) = runTest {
        val f = CollaborationHistoryFixture(databaseOwner())
        try {
            f.activate()
            block(f)
        } finally {
            f.close()
        }
    }
}
