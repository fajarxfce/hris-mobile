@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveSpaceInboxPageTest {
    @Test
    fun retainedPendingRowsEmitWithoutNativeReadinessOrCredentialRenewal() = runTest {
        val f = SpaceInboxPageFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        val result =
            assertIs<AppResult.Success<CollaborationSpaceInboxPage>>(f.observer(f.access).first())
        assertEquals(f.page(), result.value)
        assertEquals(
            CollaborationMessageDelivery.PENDING,
            assertIs<CollaborationSpaceInboxPage.Items>(result.value)
                .entries
                .single()
                .newest
                ?.preview
                ?.delivery,
        )
        assertEquals(1, f.observations)
        assertEquals(1, f.stops)
        assertEquals(0, f.base.native.pageCalls)
    }

    @Test
    fun equalPagesAndTokenRotationDoNotRestartOrRepeatRows() = runTest {
        val f = SpaceInboxPageFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceInboxPage>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access).toList(values)
            }
        runCurrent()
        val session = f.base.native.session
        f.base.identity.value =
            AppResult.Success(session.copy(tokens = session.tokens.copy(accessToken = "rotated")))
        f.pages.value = AppResult.Success(f.page().copy(entries = f.page().entries.toList()))
        runCurrent()
        assertEquals<List<AppResult<CollaborationSpaceInboxPage>>>(
            listOf(AppResult.Success(f.page())),
            values,
        )
        assertEquals(1, f.observations)
        job.cancelAndJoin()
        assertEquals(1, f.stops)
    }

    @Test
    fun exhaustionAndRestartNeverStartAnotherPageRead() = runTest {
        val f = SpaceInboxPageFixture()
        f.source = {
            flowOf(
                AppResult.Success(f.page()),
                AppResult.Success(CollaborationSpaceInboxPage.Restart),
            )
        }
        val values = mutableListOf<AppResult<CollaborationSpaceInboxPage>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access, "previous", 1).toList(values)
            }
        runCurrent()
        assertEquals<List<AppResult<CollaborationSpaceInboxPage>>>(
            listOf(
                AppResult.Success(f.page()),
                AppResult.Success(CollaborationSpaceInboxPage.Restart),
            ),
            values,
        )
        assertEquals<List<Pair<String?, Int>>>(listOf("previous" to 1), f.queries)
        assertEquals(1, f.stops)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, f.observations)
        job.cancelAndJoin()
    }

    @Test
    fun invalidRequestsAndKnownDenialNeverReadTheIndex() = runTest {
        val f = SpaceInboxPageFixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.observer(f.access, limit = 51).first()).failure.kind,
        )
        f.base.base.deny()
        assertEquals(AppResult.Failed(f.base.base.denied), f.observer(f.access).first())
        assertEquals(0, f.observations)
    }

    @Test
    fun malformedPagesAndStorageFailuresNeverMasqueradeAsEmptyHistory() = runTest {
        val f = SpaceInboxPageFixture()
        f.source = {
            flowOf(
                AppResult.Success(f.page().copy(version = null)),
                AppResult.Failed(f.failure),
                AppResult.Success(f.page()),
            )
        }
        val values = mutableListOf<AppResult<CollaborationSpaceInboxPage>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access).toList(values)
            }
        runCurrent()
        assertEquals(3, values.size)
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(values.first()).failure.kind,
        )
        assertEquals(AppResult.Failed(f.failure), values[1])
        assertEquals(AppResult.Success(f.page()), values.last())
        job.cancelAndJoin()
    }

    @Test
    fun denialSurvivesFailedRecoveryAndOnlyCurrentAdmittedFactsReopen() = runTest {
        val f = SpaceInboxPageFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceInboxPage>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access).toList(values)
            }
        runCurrent()
        f.base.base.deny()
        runCurrent()
        assertEquals(AppResult.Failed(f.base.base.denied), values.last())
        assertEquals(1, f.stops)
        f.base.base.gate.value = AppResult.Failed(f.failure)
        runCurrent()
        f.base.currentAdmission = { AppResult.Failed(f.failure) }
        f.base.base.gate.value = AppResult.Success(f.base.base.open)
        runCurrent()
        assertEquals(AppResult.Failed(f.base.base.denied), values.last())
        assertEquals(1, f.observations)
        f.base.base.gate.value = AppResult.Failed(f.failure)
        runCurrent()
        f.base.currentAdmission = { AppResult.Success(f.base.base.open) }
        f.base.base.gate.value = AppResult.Success(f.base.base.open)
        runCurrent()
        assertEquals(AppResult.Success(f.page()), values.last())
        assertEquals(2, f.observations)
        job.cancelAndJoin()
        assertEquals(2, f.stops)
    }

    @Test
    fun rowsAreDetachedBeforeCurrentAdmissionCanSuspend() = runTest {
        val f = SpaceInboxPageFixture()
        val rows = mutableListOf(f.entry())
        f.pages.value = AppResult.Success(f.page().copy(entries = rows))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        f.base.currentAdmission = {
            if (++reads == 2) {
                entered.complete(Unit)
                release.await()
            }
            AppResult.Success(f.base.base.open)
        }
        val result = async { f.observer(f.access).first() }
        entered.await()
        rows.clear()
        release.complete(Unit)
        assertEquals(AppResult.Success(f.page()), result.await())
        assertEquals(1, f.stops)
    }

    @Test
    fun accountReplacementRejectsLateUncooperativePageAdmissionAndJoinsItsReader() = runTest {
        val f = SpaceInboxPageFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        f.base.currentAdmission = {
            if (++reads == 2)
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            AppResult.Success(f.base.base.open)
        }
        val values = mutableListOf<AppResult<CollaborationSpaceInboxPage>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access).toList(values)
            }
        entered.await()
        f.base.identity.value = AppResult.Success(null)
        runCurrent()
        assertTrue(values.isEmpty())
        release.complete(Unit)
        runCurrent()
        assertFalse(values.any { it is AppResult.Success })
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        job.cancelAndJoin()
        assertEquals(1, f.stops)
    }

    @Test
    fun cancellationWaitsForUncooperativeStorageAndPublishesNoLateRows() = runTest {
        val f = SpaceInboxPageFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.source = {
            flow {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
                emit(AppResult.Success(f.page()))
            }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceInboxPage>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access).toList(values)
            }
        entered.await()
        job.cancel()
        runCurrent()
        assertFalse(job.isCompleted)
        release.complete(Unit)
        job.join()
        assertTrue(values.isEmpty())
        assertEquals(1, f.stops)
    }
}
