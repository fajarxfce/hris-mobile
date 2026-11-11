@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkSyncConnection
import dev.fajar.starter.collaboration.data.dto.SdkSyncBatch
import dev.fajar.starter.collaboration.data.dto.SdkSyncProblem
import dev.fajar.starter.collaboration.data.repositories.SdkSyncLifecycle
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

class SdkFirstSyncTest {
    @Test
    fun concurrentWaitersShareTheOwnedConnectionAndWaitForAnActualResponse() = runTest {
        val connection = FirstSyncConnection()
        var acquisitions = 0
        val owner =
            SdkSyncLifecycle(backgroundScope) {
                acquisitions++
                connection
            }
        val first = async { owner.awaitFirstSync() }
        val second = async { owner.awaitFirstSync() }
        runCurrent()
        assertEquals(1, acquisitions)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        connection.completedFirstSync.value = true
        assertEquals(AppResult.Success(Unit), first.await())
        assertEquals(AppResult.Success(Unit), second.await())
        assertEquals(AppResult.Success(Unit), owner.awaitFirstSync())
        assertEquals(1, acquisitions)
        owner.stop()
        assertEquals(1, connection.releases)
    }

    @Test
    fun timingOutTheWaitKeepsTheOriginalProducerForItsLateResponse() = runTest {
        val connection = FirstSyncConnection()
        var acquisitions = 0
        val owner =
            SdkSyncLifecycle(backgroundScope) {
                acquisitions++
                connection
            }
        val waiting = async { owner.awaitFirstSync() }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(
            "client.collaboration_sync_pending",
            assertIs<AppResult.Failed>(waiting.await()).failure.code,
        )
        assertEquals(0, connection.releases)
        connection.completedFirstSync.value = true
        assertEquals(AppResult.Success(Unit), owner.awaitFirstSync())
        assertEquals(1, acquisitions)
        owner.stop()
        assertEquals(1, connection.releases)
    }

    @Test
    fun cancelledWaiterLeavesNativeWorkOwnedUntilItsExplicitShutdown() = runTest {
        val connection = FirstSyncConnection()
        val owner = SdkSyncLifecycle(backgroundScope) { connection }
        val waiting = async { owner.awaitFirstSync() }
        runCurrent()
        waiting.cancelAndJoin()
        assertEquals(0, connection.releases)
        connection.completedFirstSync.value = true
        assertEquals(AppResult.Success(Unit), owner.awaitFirstSync())
        owner.stop()
        assertEquals(1, connection.releases)
    }

    @Test
    fun shutdownWakesAWaiterAndALateResponseCannotReopenTheConnection() = runTest {
        val connection = FirstSyncConnection()
        val owner = SdkSyncLifecycle(backgroundScope) { connection }
        val waiting = async { owner.awaitFirstSync() }
        runCurrent()
        owner.stop()
        assertEquals(
            "client.collaboration_sync_stopped",
            assertIs<AppResult.Failed>(waiting.await()).failure.code,
        )
        connection.completedFirstSync.value = true
        assertEquals(
            "client.collaboration_sync_stopped",
            assertIs<AppResult.Failed>(owner.awaitFirstSync()).failure.code,
        )
        assertEquals(1, connection.releases)
    }

    @Test
    fun authoritativeDenialWinsOverAnObservedSuccessAndCannotBecomeReady() = runTest {
        val connection = FirstSyncConnection()
        val owner = SdkSyncLifecycle(backgroundScope) { connection }
        val waiting = async { owner.awaitFirstSync() }
        runCurrent()
        connection.completedFirstSync.value = true
        connection.problems.value = SdkSyncProblem("M_FORBIDDEN", terminal = true)
        assertEquals(
            FailureKind.AccessDenied,
            assertIs<AppResult.Failed>(waiting.await()).failure.kind,
        )
        assertEquals(
            FailureKind.AccessDenied,
            assertIs<AppResult.Failed>(owner.awaitFirstSync()).failure.kind,
        )
        owner.stop()
    }
}

private class FirstSyncConnection : CollaborationSdkSyncConnection {
    override val batches = MutableStateFlow<SdkSyncBatch?>(null)
    override val completedFirstSync = MutableStateFlow(false)
    override val problems = MutableStateFlow<SdkSyncProblem?>(null)
    var releases = 0

    override suspend fun start(): SdkSyncProblem? = problems.value?.takeIf { it.terminal }

    override suspend fun stop() {
        releases++
    }
}
