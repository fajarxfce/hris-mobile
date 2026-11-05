package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkSyncConnection
import dev.fajar.starter.collaboration.data.dto.SdkSyncProblem
import dev.fajar.starter.collaboration.data.repositories.SdkSyncLifecycle
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SdkSyncLifecycleTest {
    @Test
    fun finalNativeDenialSurvivesCollectorDisposalAndFailedCleanup() = runTest {
        for (failCleanup in listOf(false, true)) {
            val connection = Connection()
            val owner = SdkSyncLifecycle(backgroundScope) { connection }
            owner.start()
            connection.onStop = {
                connection.problems.value = SdkSyncProblem("M_UNKNOWN_TOKEN", terminal = true)
                if (failCleanup) error("Fictional termination failure")
            }
            val result = owner.stop()
            assertEquals(failCleanup, result is AppResult.Failed)
            assertEquals(FailureKind.Unauthorized, owner.failures.value?.kind)
        }
    }

    @Test
    fun structuredSyncFailuresReachOwnerAndRecoveryClearsOnlyTransientFailure() = runTest {
        val connection = Connection()
        val owner = SdkSyncLifecycle(backgroundScope) { connection }
        assertIs<AppResult.Success<Unit>>(owner.start())
        connection.problems.value = SdkSyncProblem("M_LIMIT_EXCEEDED", 1_001)
        runCurrent()
        assertEquals(FailureKind.Service, owner.failures.value?.kind)
        assertEquals(2, owner.failures.value?.retryAfterSeconds)
        connection.problems.value = null
        runCurrent()
        assertNull(owner.failures.value)
        connection.problems.value = SdkSyncProblem("M_FORBIDDEN", terminal = true)
        runCurrent()
        assertEquals(FailureKind.AccessDenied, owner.failures.value?.kind)
        assertEquals(
            FailureKind.AccessDenied,
            assertIs<AppResult.Failed>(owner.start()).failure.kind,
        )
        owner.stop()
        connection.problems.value = null
        runCurrent()
        assertEquals(FailureKind.AccessDenied, owner.failures.value?.kind)
    }

    @Test
    fun concurrentStartsShareOneAcquisitionAndTerminationIsIdempotent() = runTest {
        var acquisitions = 0
        val release = CompletableDeferred<Unit>()
        val connection = Connection()
        val owner =
            SdkSyncLifecycle(backgroundScope) {
                acquisitions++
                release.await()
                connection
            }
        val first = async { owner.start() }
        val second = async { owner.start() }
        runCurrent()
        assertEquals(1, acquisitions)
        release.complete(Unit)
        assertEquals(AppResult.Success(Unit), first.await())
        assertEquals(AppResult.Success(Unit), second.await())
        assertEquals(AppResult.Success(Unit), owner.start())
        assertEquals(1, acquisitions)
        assertEquals(AppResult.Success(Unit), owner.stop())
        assertEquals(AppResult.Success(Unit), owner.stop())
        assertEquals(1, connection.releases)
        assertEquals(
            "client.collaboration_sync_stopped",
            assertIs<AppResult.Failed>(owner.start()).failure.code,
        )
    }

    @Test
    fun cancelledStarterDoesNotAbandonLateNativeResource() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val connection = Connection()
        val owner =
            SdkSyncLifecycle(backgroundScope) {
                entered.complete(Unit)
                release.await()
                connection
            }
        val caller = async { owner.start() }
        entered.await()
        caller.cancelAndJoin()
        val shutdown = async { owner.stop() }
        runCurrent()
        assertFalse(shutdown.isCompleted)
        release.complete(Unit)
        assertEquals(AppResult.Success(Unit), shutdown.await())
        assertEquals(1, connection.releases)
    }

    @Test
    fun acquisitionThatFinishesAfterShutdownCannotPublishStartedState() = runTest {
        val release = CompletableDeferred<Unit>()
        val connection = Connection()
        val owner =
            SdkSyncLifecycle(backgroundScope) {
                release.await()
                connection
            }
        val caller = async { owner.start() }
        runCurrent()
        val shutdown = async { owner.stop() }
        runCurrent()
        release.complete(Unit)
        assertEquals(
            "client.collaboration_sync_stopped",
            assertIs<AppResult.Failed>(caller.await()).failure.code,
        )
        assertEquals(AppResult.Success(Unit), shutdown.await())
        assertEquals(1, connection.releases)
    }

    @Test
    fun boundedStartWaitRetainsAcquisitionForNextExplicitCall() = runTest {
        var acquisitions = 0
        val release = CompletableDeferred<Unit>()
        val connection = Connection()
        val owner =
            SdkSyncLifecycle(backgroundScope) {
                acquisitions++
                release.await()
                connection
            }
        val caller = async { owner.start() }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(
            "client.collaboration_sync_pending",
            assertIs<AppResult.Failed>(caller.await()).failure.code,
        )
        assertEquals(1, acquisitions)
        release.complete(Unit)
        runCurrent()
        assertEquals(AppResult.Success(Unit), owner.start())
        assertEquals(1, acquisitions)
        assertEquals(AppResult.Success(Unit), owner.stop())
        assertEquals(1, connection.releases)
    }

    @Test
    fun failedAcquisitionRetriesOnlyOnAnExplicitNewStartAndIsNotRetriedByStop() = runTest {
        var acquisitions = 0
        val connection = Connection()
        val owner =
            SdkSyncLifecycle(backgroundScope) {
                if (++acquisitions == 1) error("Fictional acquisition failure")
                connection
            }
        assertIs<AppResult.Failed>(owner.start())
        runCurrent()
        assertEquals(1, acquisitions)
        assertEquals(AppResult.Success(Unit), owner.start())
        assertEquals(2, acquisitions)
        assertEquals(AppResult.Success(Unit), owner.stop())
        val failed =
            SdkSyncLifecycle(backgroundScope) {
                acquisitions++
                error("Failed again")
            }
        assertIs<AppResult.Failed>(failed.start())
        assertEquals(3, acquisitions)
        assertEquals(AppResult.Success(Unit), failed.stop())
        assertEquals(3, acquisitions)
    }

    @Test
    fun failedTerminationKeepsTheSameConnectionAndCannotStartAnother() = runTest {
        var acquisitions = 0
        val connection = Connection().apply { onStop = { error("Native task has not terminated") } }
        val owner =
            SdkSyncLifecycle(backgroundScope) {
                acquisitions++
                connection
            }
        assertEquals(AppResult.Success(Unit), owner.start())
        assertIs<AppResult.Failed>(owner.stop())
        assertEquals(0, connection.releases)
        assertIs<AppResult.Failed>(owner.start())
        assertEquals(1, acquisitions)
        connection.onStop = {}
        assertEquals(AppResult.Success(Unit), owner.stop())
        assertEquals(1, connection.releases)
    }

    @Test
    fun cancelledTerminationRetainsNativeHandleForLeaseCleanup() = runTest {
        val entered = CompletableDeferred<Unit>()
        val connection =
            Connection().apply {
                onStop = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        val owner = SdkSyncLifecycle(backgroundScope) { connection }
        assertEquals(AppResult.Success(Unit), owner.start())
        val stopping = async { owner.stop() }
        entered.await()
        stopping.cancelAndJoin()
        assertEquals(0, connection.releases)
        connection.onStop = {}
        assertEquals(AppResult.Success(Unit), owner.stop())
        assertEquals(1, connection.releases)
    }

    @Test
    fun concurrentStopsSerializeNativeTerminationAndNeverAcquireAfterEmptyClose() = runTest {
        val release = CompletableDeferred<Unit>()
        val connection = Connection().apply { onStop = { release.await() } }
        val owner = SdkSyncLifecycle(backgroundScope) { connection }
        assertEquals(AppResult.Success(Unit), owner.start())
        val first = async { owner.stop() }
        val second = async { owner.stop() }
        runCurrent()
        assertEquals(1, connection.calls)
        release.complete(Unit)
        assertEquals(AppResult.Success(Unit), first.await())
        assertEquals(AppResult.Success(Unit), second.await())
        assertEquals(1, connection.releases)
        val empty = SdkSyncLifecycle(backgroundScope) { error("Closed before acquiring") }
        assertEquals(AppResult.Success(Unit), empty.stop())
        assertIs<AppResult.Failed>(empty.start())
    }
}

private class Connection : CollaborationSdkSyncConnection {
    override val completedFirstSync = MutableStateFlow(false)
    override val problems = MutableStateFlow<SdkSyncProblem?>(null)

    override suspend fun start(): SdkSyncProblem? = problems.value?.takeIf { it.terminal }

    var calls = 0
    var releases = 0
    var onStop: suspend () -> Unit = {}

    override suspend fun stop() {
        if (releases != 0) return
        calls++
        onStop()
        releases++
    }
}
