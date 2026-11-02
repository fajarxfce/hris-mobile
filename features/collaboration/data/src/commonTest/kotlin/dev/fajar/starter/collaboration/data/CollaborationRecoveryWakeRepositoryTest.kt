@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.StoredCollaborationRecoveryWakeRepository
import dev.fajar.starter.collaboration.domain.entities.CollaborationRecoveryWake
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationRecoveryWakeRepositoryTest {
    @Test
    fun fixedKeyAndFreshGenerationsRemainIndependentOfAcknowledgmentOrAdapterRecreation() =
        runTest {
            val store = WakeStore()
            val firstRepository = StoredCollaborationRecoveryWakeRepository(store)
            val first =
                assertIs<AppResult.Success<CollaborationRecoveryWake>>(firstRepository.request())
                    .value
            assertEquals(first.generation, Uuid.parse(first.generation).toString())
            assertEquals(AppResult.Success(true), firstRepository.acknowledge(first))
            assertEquals(AppResult.Success(null), firstRepository.pending())
            val restarted = StoredCollaborationRecoveryWakeRepository(store)
            val next =
                assertIs<AppResult.Success<CollaborationRecoveryWake>>(restarted.request()).value
            assertNotEquals(first, next)
            assertEquals(AppResult.Success(false), restarted.acknowledge(first))
            assertEquals(AppResult.Success(next), restarted.pending())
            assertEquals(setOf(CollaborationRecoveryWake.WORK_KEY), store.keys.toSet())
            assertEquals(listOf(32, 32), store.capacities)
        }

    @Test
    fun capacityExhaustionIsAnExplicitConflictWithoutHiddenRetry() = runTest {
        val store = WakeStore().apply { full = true }
        val repository = StoredCollaborationRecoveryWakeRepository(store)
        val failed = assertIs<AppResult.Failed>(repository.request())
        assertEquals(FailureKind.Conflict, failed.failure.kind)
        assertEquals("client.collaboration_recovery_capacity", failed.failure.code)
        assertEquals(1, store.capacities.size)
        assertNull(store.record)
    }

    @Test
    fun malformedOrForeignRecordsCannotBecomeRecoveryCheckpoints() = runTest {
        val store = WakeStore()
        val repository = StoredCollaborationRecoveryWakeRepository(store)
        for (record in
            listOf(
                WorkWakeRecord("other-task", VALID_GENERATION, true),
                WorkWakeRecord(CollaborationRecoveryWake.WORK_KEY, "malformed", true),
                WorkWakeRecord(
                    CollaborationRecoveryWake.WORK_KEY,
                    VALID_GENERATION.uppercase(),
                    false,
                ),
            )) {
            store.record = record
            assertEquals(
                FailureKind.Storage,
                assertIs<AppResult.Failed>(repository.pending()).failure.kind,
            )
        }
        store.record = WorkWakeRecord(CollaborationRecoveryWake.WORK_KEY, VALID_GENERATION, false)
        assertEquals(AppResult.Success(null), repository.pending())
        assertEquals(
            FailureKind.Storage,
            assertIs<AppResult.Failed>(
                    repository.acknowledge(CollaborationRecoveryWake("malformed"))
                )
                .failure
                .kind,
        )
        assertEquals(0, store.acks)
    }

    @Test
    fun writeResponseMustMatchTheRequestedKeyGenerationAndPendingState() = runTest {
        for (rewrite in
            listOf<(WorkWakeRecord) -> WorkWakeRecord>(
                { it.copy(key = "another-key") },
                { it.copy(generation = VALID_GENERATION) },
                { it.copy(pending = false) },
            )) {
            val store = WakeStore().apply { response = rewrite }
            val result = StoredCollaborationRecoveryWakeRepository(store).request()
            assertEquals(FailureKind.Storage, assertIs<AppResult.Failed>(result).failure.kind)
            assertNotNull(store.record)
        }
    }

    @Test
    fun rawFailuresAreSanitizedAndCancellationAfterCommitKeepsThePendingState() = runTest {
        val store = WakeStore()
        val repository = StoredCollaborationRecoveryWakeRepository(store)
        store.failure = IllegalStateException("private diagnostic")
        val results =
            listOf(
                repository.pending(),
                repository.request(),
                repository.acknowledge(CollaborationRecoveryWake(VALID_GENERATION)),
            )
        for (result in results) {
            val failure = assertIs<AppResult.Failed>(result).failure
            assertEquals(FailureKind.Storage, failure.kind)
            assertFalse(failure.message.contains("private diagnostic"))
        }
        store.failure = null
        store.afterWrite = { currentCoroutineContext().cancel() }
        val job = launch {
            repository.request()
            error("Cancelled persistence result")
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(store.record!!.pending)
        val restarted = StoredCollaborationRecoveryWakeRepository(store)
        assertNotNull(
            assertIs<AppResult.Success<CollaborationRecoveryWake?>>(restarted.pending()).value
        )
    }

    @Test
    fun cancellationAfterAcknowledgmentPropagatesAndDoesNotReopenTheGeneration() = runTest {
        val store = WakeStore()
        val repository = StoredCollaborationRecoveryWakeRepository(store)
        val wake =
            assertIs<AppResult.Success<CollaborationRecoveryWake>>(repository.request()).value
        store.afterAck = { currentCoroutineContext().cancel() }
        val job = launch {
            repository.acknowledge(wake)
            error("Cancelled acknowledgment result")
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(store.record!!.pending)
        assertEquals(AppResult.Success(null), repository.pending())
    }

    private companion object {
        const val VALID_GENERATION = "abff1b5d-1234-4234-8234-000000000001"
    }
}

private class WakeStore : WorkWakeStore {
    var record: WorkWakeRecord? = null
    var full = false
    var failure: Exception? = null
    var response: (WorkWakeRecord) -> WorkWakeRecord = { it }
    var afterWrite: suspend () -> Unit = {}
    var afterAck: suspend () -> Unit = {}
    val keys = mutableListOf<String>()
    val capacities = mutableListOf<Int>()
    var acks = 0

    override suspend fun get(key: String): WorkWakeRecord? {
        failure?.let { throw it }
        keys += key
        return record
    }

    override suspend fun request(key: String, generation: String, maxKeys: Int): WorkWakeRecord? {
        failure?.let { throw it }
        keys += key
        capacities += maxKeys
        if (full) return null
        val value = WorkWakeRecord(key, generation, true)
        record = value
        afterWrite()
        return response(value)
    }

    override suspend fun acknowledge(key: String, generation: String): Boolean {
        failure?.let { throw it }
        keys += key
        acks++
        val current = record
        val matches =
            current != null &&
                current.key == key &&
                current.generation == generation &&
                current.pending
        if (matches) record = current!!.copy(pending = false)
        afterAck()
        return matches
    }
}
