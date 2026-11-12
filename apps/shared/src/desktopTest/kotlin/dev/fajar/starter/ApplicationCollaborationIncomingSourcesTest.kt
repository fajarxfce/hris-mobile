@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationState
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.IncomingSourcesFixture
import dev.fajar.starter.fixtures.recoveryId
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ApplicationCollaborationIncomingSourcesTest {
    @Test
    fun constructionAndPendingParentDoNotObserveOrAcquire() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        assertTrue(f.parents.isEmpty())
        f.worker.states.value = ApplicationCollaborationState.Pending
        f.start(backgroundScope)
        runCurrent()
        assertTrue(f.parents.isEmpty())
        assertTrue(f.keys.isEmpty())
        assertTrue(f.sync.isEmpty())
    }

    @Test
    fun unsupportedHostDoesNotSubscribeToNativeSources() = runTest {
        val f = IncomingSourcesFixture(testScheduler, AppPlatform.Desktop)
        val job = f.start(backgroundScope)
        runCurrent()
        assertTrue(job.isCompleted)
        assertTrue(f.parents.isEmpty())
    }

    @Test
    fun localStartupFactsCannotStartCollectorsBeforeNativePublication() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        f.start(backgroundScope)
        runCurrent()
        assertEquals(listOf(f.runtime), f.runtimeRequests)
        assertTrue(f.keys.isEmpty())
        assertTrue(f.sync.isEmpty())
        f.publish()
        runCurrent()
        assertEquals(listOf(f.runtime), f.keys)
        assertEquals(listOf(f.runtime), f.sync)
    }

    @Test
    fun duplicatePublicationDoesNotRestartHealthyCollectors() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        val values = MutableSharedFlow<AppResult<CollaborationSpaceRuntimePublication?>>(replay = 1)
        values.emit(AppResult.Success(f.publication))
        f.publicationBody = { values }
        f.start(backgroundScope)
        runCurrent()
        repeat(5) {
            values.emit(AppResult.Success(f.publication))
            runCurrent()
        }
        assertEquals(1, f.keys.size)
        assertEquals(1, f.sync.size)
        assertEquals(0, f.keyStops)
    }

    @Test
    fun sameCoordinatesWithNewInstanceWaitForOldCollectorDisposal() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.publish()
        f.start(backgroundScope)
        runCurrent()
        try {
            f.afterKeys = {
                entered.complete(Unit)
                release.await()
            }
            f.publications.value =
                AppResult.Success(f.publication.copy(instanceId = recoveryId(805)))
            runCurrent()
            assertTrue(entered.isCompleted)
            assertEquals(1, f.keys.size)
            assertEquals(1, f.sync.size)
            release.complete(Unit)
            runCurrent()
            assertEquals(2, f.keys.size)
            assertEquals(2, f.sync.size)
            assertEquals(1, f.keyStops)
            assertEquals(1, f.syncStops)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun runtimeRetirementCancelsBothCollectorsWithoutReopeningIt() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        f.publish()
        f.start(backgroundScope)
        runCurrent()
        f.publications.value = AppResult.Success(null)
        runCurrent()
        assertEquals(1, f.keyStops)
        assertEquals(1, f.syncStops)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, f.keys.size)
    }

    @Test
    fun completedKeyAttemptWaitsForForegroundInsteadOfRetrying() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        f.keyBody = { flowOf(AppResult.Failed(Failure(FailureKind.Storage, "fixture"))) }
        f.publish()
        f.start(backgroundScope)
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, f.keys.size)
        assertEquals(1, f.keyStops)
        assertEquals(0, f.syncStops)
        f.keyBody = { flow { awaitCancellation() } }
        f.app.foregroundEntered()
        runCurrent()
        assertEquals(2, f.keys.size)
        assertEquals(2, f.sync.size)
        assertEquals(1, f.syncStops)
    }

    @Test
    fun unexpectedKeyFailureDoesNotCancelSyncOrCreateATimer() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        f.keyBody = { flow { error("fixture callback failure") } }
        f.publish()
        f.start(backgroundScope)
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, f.keys.size)
        assertEquals(1, f.keyStops)
        assertEquals(0, f.syncStops)
    }

    @Test
    fun foreignStartupCannotObserveAnotherParentsRuntime() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        val foreign =
            f.startup.copy(
                owner =
                    f.startup.owner.copy(parent = f.worker.parent.copy(accountId = recoveryId(900)))
            )
        f.startups.value = AppResult.Success(foreign)
        f.publish()
        f.start(backgroundScope)
        runCurrent()
        assertTrue(f.runtimeRequests.isEmpty())
        assertTrue(f.keys.isEmpty())
    }

    @Test
    fun rootReplacementCancelsOldCollectorsBeforeNewPublication() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        f.publish()
        f.start(backgroundScope)
        runCurrent()
        val next = f.startup.copy(owner = f.startup.owner.copy(spaceId = recoveryId(806)))
        val runtime = CollaborationSpaceRuntimeReady(next.owner, next.deviceId, next.operationId)
        f.startups.value = AppResult.Success(next)
        runCurrent()
        assertEquals(1, f.keyStops)
        assertEquals(1, f.syncStops)
        assertEquals(listOf(f.runtime, runtime), f.runtimeRequests)
        assertEquals(1, f.keys.size)
        f.publications.value =
            AppResult.Success(CollaborationSpaceRuntimePublication(runtime, recoveryId(807)))
        runCurrent()
        assertEquals(listOf(f.runtime, runtime), f.keys)
    }

    @Test
    fun parentLossCancelsAllSourcesAndLatePublicationCannotReviveThem() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        f.publish()
        f.start(backgroundScope)
        runCurrent()
        f.worker.states.value = ApplicationCollaborationState.Stopped(null)
        runCurrent()
        f.publications.value = AppResult.Success(f.publication.copy(instanceId = recoveryId(805)))
        runCurrent()
        assertEquals(1, f.keys.size)
        assertEquals(1, f.keyStops)
        assertEquals(1, f.syncStops)
        assertEquals(1, f.startupStops)
        assertEquals(1, f.publicationStops)
    }

    @Test
    fun foregroundEntryStartsOneFreshObservationPass() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        f.publish()
        f.start(backgroundScope)
        runCurrent()
        f.app.foregroundEntered()
        runCurrent()
        assertEquals(2, f.parents.size)
        assertEquals(2, f.runtimeRequests.size)
        assertEquals(2, f.keys.size)
        assertEquals(2, f.sync.size)
        assertEquals(1, f.startupStops)
        assertEquals(1, f.publicationStops)
    }

    @Test
    fun secondProcessCollectorWaitsBeforeObservingOrRetaining() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        f.publish()
        val first = f.start(backgroundScope)
        f.start(backgroundScope)
        runCurrent()
        assertEquals(1, f.parents.size)
        assertEquals(1, f.keys.size)
        first.cancelAndJoin()
        runCurrent()
        assertEquals(2, f.parents.size)
        assertEquals(2, f.keys.size)
        assertEquals(1, f.keyStops)
    }

    @Test
    fun failedPublicationObservationWaitsForRealForegroundRecovery() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        f.publicationBody = { flow { error("fixture observation") } }
        f.publish()
        val process = f.start(backgroundScope)
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertTrue(process.isActive)
        assertEquals(1, f.runtimeRequests.size)
        assertTrue(f.keys.isEmpty())
        f.publicationBody = { f.publications }
        f.app.foregroundEntered()
        runCurrent()
        assertEquals(2, f.runtimeRequests.size)
        assertEquals(1, f.keys.size)
    }

    @Test
    fun publicationStreamCompletionClearsItsLastReadyValue() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        val stream = Channel<AppResult<CollaborationSpaceRuntimePublication?>>(Channel.BUFFERED)
        stream.send(AppResult.Success(f.publication))
        f.publicationBody = { stream.receiveAsFlow() }
        f.start(backgroundScope)
        runCurrent()
        assertEquals(1, f.keys.size)
        stream.close()
        runCurrent()
        assertEquals(1, f.keyStops)
        assertEquals(1, f.syncStops)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, f.keys.size)
    }

    @Test
    fun startupStreamCompletionCancelsPublishedRuntimeObservation() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        val stream = Channel<AppResult<CollaborationSpaceIncomingStartup?>>(Channel.BUFFERED)
        stream.send(AppResult.Success(f.startup))
        f.startupBody = { stream.receiveAsFlow() }
        f.publish()
        f.start(backgroundScope)
        runCurrent()
        assertEquals(1, f.keys.size)
        stream.close()
        runCurrent()
        assertEquals(1, f.publicationStops)
        assertEquals(1, f.keyStops)
        assertEquals(1, f.syncStops)
    }

    @Test
    fun syncResultFailureDoesNotDiscardLaterIndependentBatches() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        val batches = Channel<AppResult<Boolean>>(Channel.BUFFERED)
        val emitted = mutableListOf<AppResult<Boolean>>()
        f.syncBody = { batches.receiveAsFlow().onEach { emitted += it } }
        val failed: AppResult<Boolean> = AppResult.Failed(Failure(FailureKind.Storage, "fixture"))
        batches.send(failed)
        f.publish()
        f.start(backgroundScope)
        runCurrent()
        batches.send(AppResult.Success(true))
        runCurrent()
        assertEquals(listOf(failed, AppResult.Success(true)), emitted)
        assertEquals(1, f.sync.size)
        assertEquals(0, f.syncStops)
        assertEquals(0, f.keyStops)
    }

    @Test
    fun processCancellationWaitsForPendingCollectorCleanup() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            f.publish()
            val process = f.start(backgroundScope)
            runCurrent()
            f.afterKeys = {
                entered.complete(Unit)
                release.await()
            }
            process.cancel()
            runCurrent()
            assertTrue(entered.isCompleted)
            assertFalse(process.isCompleted)
            release.complete(Unit)
            process.join()
            assertEquals(1, f.keyStops)
            assertEquals(1, f.syncStops)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun foregroundSignalsBeforeProcessStartDoNotCreateWork() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        repeat(5) { f.app.foregroundEntered() }
        assertTrue(f.parents.isEmpty())
        f.publish()
        f.start(backgroundScope)
        runCurrent()
        assertEquals(1, f.parents.size)
        assertEquals(1, f.keys.size)
    }

    @Test
    fun typedCredentialFailureCancelsAndLaterPublicationRecovers() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        f.publish()
        f.start(backgroundScope)
        runCurrent()
        f.publications.value = AppResult.Failed(Failure(FailureKind.Unauthorized, "fixture"))
        runCurrent()
        assertEquals(1, f.keyStops)
        assertEquals(1, f.syncStops)
        f.publish()
        runCurrent()
        assertEquals(2, f.keys.size)
        assertEquals(2, f.sync.size)
    }

    @Test
    fun foreignPublicationCannotStartCollectors() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        f.publications.value =
            AppResult.Success(
                f.publication.copy(runtime = f.runtime.copy(deviceId = recoveryId(999)))
            )
        f.start(backgroundScope)
        runCurrent()
        assertTrue(f.keys.isEmpty())
        f.publish()
        runCurrent()
        assertEquals(1, f.keys.size)
    }

    @Test
    fun unexpectedSyncFailureLeavesKeysObservedAndHasNoRetryTimer() = runTest {
        val f = IncomingSourcesFixture(testScheduler)
        f.syncBody = { flow { error("fixture sync stream") } }
        f.publish()
        f.start(backgroundScope)
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, f.sync.size)
        assertEquals(1, f.syncStops)
        assertEquals(0, f.keyStops)
    }
}
