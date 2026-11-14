@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveSpaceInboxDestinationTest {
    @Test
    fun oneSubscriptionReturnsOnlyChangedMetadataAndStartsNoWork() = runTest {
        val f = GlobalHistoryAdmissionFixture()
        val first = CollaborationSpaceInboxVersion(f.other, 1, 1)
        val second = first.copy(revision = 2, lastOrdinal = 2)
        f.source = {
            flowOf(
                AppResult.Success(null),
                AppResult.Success(first),
                AppResult.Success(first),
                AppResult.Success(second),
            )
        }
        val values = mutableListOf<AppResult<CollaborationSpaceInboxVersion?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access).toList(values)
            }
        runCurrent()
        assertEquals<List<AppResult<CollaborationSpaceInboxVersion?>>>(
            listOf(AppResult.Success(null), AppResult.Success(first), AppResult.Success(second)),
            values,
        )
        assertEquals(1, f.observations)
        assertEquals(1, f.stops)
        assertEquals(0, f.reads)
        assertTrue(f.writes.isEmpty())
        job.cancelAndJoin()
    }

    @Test
    fun malformedMetadataAndStorageFailureAreNotReportedAsAbsence() = runTest {
        val f = GlobalHistoryAdmissionFixture()
        f.source = {
            flowOf(
                AppResult.Success(CollaborationSpaceInboxVersion("invalid", 1, 1)),
                AppResult.Failed(f.failure),
                AppResult.Success(null),
            )
        }
        val values = mutableListOf<AppResult<CollaborationSpaceInboxVersion?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access).toList(values)
            }
        runCurrent()
        assertEquals(3, values.size)
        assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(values[0]).failure.kind)
        assertEquals(AppResult.Failed(f.failure), values[1])
        assertEquals(AppResult.Success(null), values[2])
        job.cancelAndJoin()
    }

    @Test
    fun oldCredentialsAndTokenRotationRetainTheOwnedMetadataSubscription() = runTest {
        val f = GlobalHistoryAdmissionFixture()
        f.base.base.native.setup.now += 365.days
        f.base.base.native.setup.workspace = null
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access).collect()
            }
        runCurrent()
        val session = f.base.base.native.session
        f.base.base.identity.value =
            AppResult.Success(session.copy(tokens = session.tokens.copy(accessToken = "rotated")))
        runCurrent()
        assertEquals(1, f.observations)
        assertEquals(0, f.base.base.native.pageCalls)
        job.cancelAndJoin()
        assertEquals(1, f.stops)
    }

    @Test
    fun denialStaysLatchedThroughFailureUntilAnAdmittedUpdate() = runTest {
        val f = GlobalHistoryAdmissionFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceInboxVersion?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access).toList(values)
            }
        runCurrent()
        f.base.base.deny()
        runCurrent()
        assertEquals(AppResult.Failed(f.base.base.denied), values.last())
        assertEquals(1, f.stops)
        val count = values.size
        f.base.base.gate.value = AppResult.Failed(f.failure)
        f.destinations.value = AppResult.Success(CollaborationSpaceInboxVersion(f.other, 1, 1))
        runCurrent()
        assertEquals(count, values.size)
        assertEquals(1, f.observations)
        f.base.base.gate.value = AppResult.Success(f.base.base.open)
        runCurrent()
        assertEquals(f.destinations.value, values.last())
        assertEquals(2, f.observations)
        job.cancelAndJoin()
        assertEquals(2, f.stops)
    }

    @Test
    fun ownershipReplacementDiscardsAnUncooperativeLateMetadataResult() = runTest {
        val f = GlobalHistoryAdmissionFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.before = {
            if (it == "identity")
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceInboxVersion?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access).toList(values)
            }
        entered.await()
        f.base.base.identity.value = AppResult.Success(null)
        runCurrent()
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
    fun invalidOwnerAndKnownDenialNeverSubscribeToDestinationMetadata() = runTest {
        val f = GlobalHistoryAdmissionFixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.observer(f.access.copy(spaceId = "invalid")).first())
                .failure
                .kind,
        )
        f.base.base.deny()
        assertEquals(AppResult.Failed(f.base.base.denied), f.observer(f.access).first())
        assertEquals(0, f.observations)
        assertEquals(0, f.reads)
        assertTrue(f.writes.isEmpty())
    }
}
