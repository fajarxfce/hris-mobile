@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationInboxOwner
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceLocalAccess
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ApplicationCollaborationInboxOwnerTest {
    private val first =
        CollaborationSpaceLocalAccess(
            "local-session",
            "https://example.test",
            "d6000000-0000-4000-8000-000000000001",
            "d6000000-0000-4000-8000-000000000002",
        )
    private val second = first.copy(accountId = "d6000000-0000-4000-8000-000000000003")

    @Test
    fun equalOwnerAndRepeatedCollectionNeverCreateConcurrentRepairs() = runTest {
        val owners =
            MutableStateFlow<AppResult<CollaborationSpaceLocalAccess?>>(AppResult.Success(first))
        var starts = 0
        var active = 0
        val inbox =
            ApplicationCollaborationInboxOwner({ owners }) {
                flow {
                    starts++
                    active++
                    assertEquals(1, active)
                    try {
                        awaitCancellation()
                    } finally {
                        active--
                    }
                }
            }
        val one = backgroundScope.launch { inbox.collect() }
        val two = backgroundScope.launch { inbox.collect() }
        runCurrent()
        assertEquals(1, starts)
        owners.value = AppResult.Success(first.copy())
        runCurrent()
        assertEquals(1, starts)
        one.cancelAndJoin()
        runCurrent()
        assertEquals(2, starts)
        assertEquals(1, active)
        two.cancelAndJoin()
        assertEquals(0, active)
    }

    @Test
    fun replacementWaitsForThePreviousRepairsFinalCleanup() = runTest {
        val owners =
            MutableStateFlow<AppResult<CollaborationSpaceLocalAccess?>>(AppResult.Success(first))
        val release = CompletableDeferred<Unit>()
        val starts = mutableListOf<CollaborationSpaceLocalAccess>()
        val stops = mutableListOf<CollaborationSpaceLocalAccess>()
        val inbox =
            ApplicationCollaborationInboxOwner({ owners }) { access ->
                flow {
                    starts += access
                    try {
                        awaitCancellation()
                    } finally {
                        if (access == first) withContext(NonCancellable) { release.await() }
                        stops += access
                    }
                }
            }
        val job = backgroundScope.launch { inbox.collect() }
        runCurrent()
        owners.value = AppResult.Success(second)
        runCurrent()
        assertEquals(listOf(first), starts)
        assertTrue(stops.isEmpty())
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(first), stops)
        assertEquals(listOf(first, second), starts)
        job.cancelAndJoin()
        assertEquals(starts, stops)
    }

    @Test
    fun nullDenialAndStorageFailuresCloseTheOldChildUntilAdmittedRecovery() = runTest {
        for (closed in
            listOf<AppResult<CollaborationSpaceLocalAccess?>>(
                AppResult.Success(null),
                AppResult.Failed(Failure(FailureKind.AccessDenied, "Denied")),
                AppResult.Failed(Failure(FailureKind.Storage, "Unavailable")),
            )) {
            val owners =
                MutableStateFlow<AppResult<CollaborationSpaceLocalAccess?>>(
                    AppResult.Success(first)
                )
            var starts = 0
            var stops = 0
            val inbox =
                ApplicationCollaborationInboxOwner({ owners }) {
                    flow {
                        starts++
                        try {
                            awaitCancellation()
                        } finally {
                            stops++
                        }
                    }
                }
            val job = backgroundScope.launch { inbox.collect() }
            runCurrent()
            owners.value = closed
            runCurrent()
            assertEquals(1, stops)
            advanceTimeBy(86_400_000)
            runCurrent()
            assertEquals(1, starts)
            owners.value = AppResult.Success(first)
            runCurrent()
            assertEquals(2, starts)
            job.cancelAndJoin()
            assertEquals(2, stops)
        }
    }

    @Test
    fun aRepairExceptionDoesNotRestartFromEqualOwnerOrAClockTick() = runTest {
        val owners =
            MutableStateFlow<AppResult<CollaborationSpaceLocalAccess?>>(AppResult.Success(first))
        val starts = mutableListOf<CollaborationSpaceLocalAccess>()
        val inbox =
            ApplicationCollaborationInboxOwner({ owners }) { access ->
                flow {
                    starts += access
                    throw IllegalStateException("Fixture repair stopped")
                }
            }
        val job = backgroundScope.launch { inbox.collect() }
        runCurrent()
        owners.value = AppResult.Success(first.copy())
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(listOf(first), starts)
        owners.value = AppResult.Success(second)
        runCurrent()
        assertEquals(listOf(first, second), starts)
        job.cancelAndJoin()
    }

    @Test
    fun normalOwnerStreamCompletionCancelsAndJoinsItsLastRepair() = runTest {
        val emitted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var stopped = false
        val inbox =
            ApplicationCollaborationInboxOwner({
                flow {
                    emit(AppResult.Success(first))
                    finish.await()
                }
            }) {
                flow {
                    emitted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                }
            }
        val job = backgroundScope.launch { inbox.collect() }
        runCurrent()
        emitted.await()
        finish.complete(Unit)
        job.join()
        assertTrue(stopped)
    }

    @Test
    fun cancellingTheApplicationWaitsForPendingRepairCleanup() = runTest {
        val release = CompletableDeferred<Unit>()
        var stopped = false
        val owners =
            MutableStateFlow<AppResult<CollaborationSpaceLocalAccess?>>(AppResult.Success(first))
        val inbox =
            ApplicationCollaborationInboxOwner({ owners }) {
                flow {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { release.await() }
                        stopped = true
                    }
                }
            }
        val job = backgroundScope.launch { inbox.collect() }
        runCurrent()
        job.cancel()
        runCurrent()
        assertFalse(job.isCompleted)
        release.complete(Unit)
        job.join()
        assertTrue(stopped)
    }
}
