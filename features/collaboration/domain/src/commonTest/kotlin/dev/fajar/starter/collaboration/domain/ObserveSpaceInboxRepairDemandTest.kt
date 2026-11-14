@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveSpaceInboxRepairDemandTest {
    @Test
    fun finishingAnOlderCapturedPassDoesNotClaimTheLaterRequestCompleted() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val older = f.initial.latestDemand
        val newer = f.id(20)
        val active =
            f.initial.copy(
                latestDemand = newer,
                pass = CollaborationSpaceInboxRepairPass(older, null),
            )
        val completed = active.copy(generation = f.id(21), completedDemand = older, pass = null)
        f.progressSource = { flowOf(AppResult.Success(active), AppResult.Success(completed)) }
        val values = mutableListOf<AppResult<CollaborationSpaceInboxRepairSignals>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observeSignals()(f.access).toList(values)
            }
        runCurrent()
        assertEquals<List<AppResult<CollaborationSpaceInboxRepairSignals>>>(
            listOf(
                AppResult.Success(CollaborationSpaceInboxRepairSignals(newer, null)),
                AppResult.Success(CollaborationSpaceInboxRepairSignals(newer, older)),
            ),
            values,
        )
        assertEquals(1, f.progressObservations)
        assertEquals(1, f.progressStops)
        assertTrue(f.replacements.isEmpty())
        job.cancelAndJoin()
    }

    @Test
    fun clearingProgressRemovesBothSignalsAndPhysicalOnlyChangesStayQuiet() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val finished = f.initial.copy(completedDemand = f.initial.latestDemand)
        f.progressSource = {
            flowOf(
                AppResult.Success(finished),
                AppResult.Success(finished.copy(generation = f.id(30))),
                AppResult.Success(null),
                AppResult.Success(null),
            )
        }
        val values = mutableListOf<AppResult<CollaborationSpaceInboxRepairSignals>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observeSignals()(f.access).toList(values)
            }
        runCurrent()
        assertEquals<List<AppResult<CollaborationSpaceInboxRepairSignals>>>(
            listOf(
                AppResult.Success(
                    CollaborationSpaceInboxRepairSignals(
                        finished.latestDemand,
                        finished.latestDemand,
                    )
                ),
                AppResult.Success(CollaborationSpaceInboxRepairSignals(null, null)),
            ),
            values,
        )
        assertEquals(1, f.progressObservations)
        assertEquals(0, f.checkpointReads)
        job.cancelAndJoin()
    }

    @Test
    fun startingAdvancingRestartingAndAcknowledgingOnePassDoNotEmitAnotherRequest() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val first = f.initial
        val pass = CollaborationSpaceInboxRepairPass(first.latestDemand, null)
        val next =
            first.copy(
                generation = f.id(10),
                latestDemand = f.id(11),
                completedDemand = first.latestDemand,
            )
        f.progressSource = {
            flowOf(
                AppResult.Success(null),
                AppResult.Success(first),
                AppResult.Success(first.copy(generation = f.id(3), pass = pass)),
                AppResult.Success(
                    first.copy(generation = f.id(4), pass = pass.copy(after = "page-two"))
                ),
                AppResult.Success(first.copy(generation = f.id(5), pass = pass)),
                AppResult.Success(
                    first.copy(generation = f.id(6), completedDemand = first.latestDemand)
                ),
                AppResult.Success(next),
            )
        }
        val values = mutableListOf<AppResult<CollaborationSpaceInboxRepairSignals>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observeSignals()(f.access).toList(values)
            }
        runCurrent()
        assertEquals<List<AppResult<CollaborationSpaceInboxRepairSignals>>>(
            listOf(
                AppResult.Success(CollaborationSpaceInboxRepairSignals(null, null)),
                AppResult.Success(CollaborationSpaceInboxRepairSignals(first.latestDemand, null)),
                AppResult.Success(
                    CollaborationSpaceInboxRepairSignals(first.latestDemand, first.latestDemand)
                ),
                AppResult.Success(
                    CollaborationSpaceInboxRepairSignals(next.latestDemand, first.latestDemand)
                ),
            ),
            values,
        )
        assertEquals(
            listOf(null, first.latestDemand, next.latestDemand),
            values
                .map {
                    assertIs<AppResult.Success<CollaborationSpaceInboxRepairSignals>>(it)
                        .value
                        .latestDemand
                }
                .distinct(),
        )
        assertEquals(1, f.progressObservations)
        assertEquals(1, f.progressStops)
        assertTrue(f.replacements.isEmpty())
        assertEquals(0, f.checkpointReads)
        job.cancelAndJoin()
    }

    @Test
    fun oldCredentialsAndTheirRotationKeepTheSameLocalMetadataSubscription() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        val values = mutableListOf<AppResult<CollaborationSpaceInboxRepairSignals>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observeSignals()(f.access).toList(values)
            }
        runCurrent()
        f.base.identity.value =
            AppResult.Success(
                f.base.native.session.copy(
                    tokens = f.base.native.session.tokens.copy(accessToken = "rotated")
                )
            )
        runCurrent()
        assertEquals<List<AppResult<CollaborationSpaceInboxRepairSignals>>>(
            listOf(
                AppResult.Success(
                    CollaborationSpaceInboxRepairSignals(f.initial.latestDemand, null)
                )
            ),
            values,
        )
        assertEquals(1, f.progressObservations)
        assertEquals(0, f.base.native.pageCalls)
        job.cancelAndJoin()
        assertEquals(1, f.progressStops)
    }

    @Test
    fun denialRemainsClosedThroughFailedAdmissionUntilAnAdmittedUpdate() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceInboxRepairSignals>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observeSignals()(f.access).toList(values)
            }
        runCurrent()
        f.base.deny()
        runCurrent()
        assertEquals(AppResult.Failed(f.base.denied), values.last())
        assertEquals(1, f.progressStops)
        val afterDenial = values.size
        f.base.gate.value = AppResult.Failed(f.base.storageFailure)
        f.progressValues.value =
            AppResult.Success(f.initial.copy(generation = f.id(8), latestDemand = f.id(9)))
        runCurrent()
        assertEquals(afterDenial, values.size)
        assertEquals(1, f.progressObservations)
        f.base.gate.value = AppResult.Success(f.base.open)
        runCurrent()
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxRepairSignals(f.id(9), null)),
            values.last(),
        )
        assertEquals(2, f.progressObservations)
        job.cancelAndJoin()
        assertEquals(2, f.progressStops)
    }

    @Test
    fun currentOwnershipOverridesAStaleMetadataSuccessAndDisposalJoinsTheReader() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeCurrent = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceInboxRepairSignals>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observeSignals()(f.access).toList(values)
            }
        entered.await()
        f.base.identity.value = AppResult.Success(null)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertFalse(values.any { it is AppResult.Success })
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        job.cancelAndJoin()
        assertEquals(1, f.progressStops)
    }

    @Test
    fun malformedProgressAndStorageFailureRemainDistinctFromNoRequest() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        f.progressSource = {
            flowOf(
                AppResult.Success(f.initial.copy(generation = "invalid")),
                AppResult.Failed(f.base.storageFailure),
                AppResult.Success(f.initial),
            )
        }
        val values = mutableListOf<AppResult<CollaborationSpaceInboxRepairSignals>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observeSignals()(f.access).toList(values)
            }
        runCurrent()
        assertEquals(3, values.size)
        assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(values[0]).failure.kind)
        assertEquals(AppResult.Failed(f.base.storageFailure), values[1])
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxRepairSignals(f.initial.latestDemand, null)),
            values[2],
        )
        assertEquals(1, f.progressObservations)
        assertTrue(f.replacements.isEmpty())
        job.cancelAndJoin()
    }

    @Test
    fun invalidOwnerAndKnownDenialNeverSubscribeOrManufactureDemand() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(
                    f.observeSignals()(f.access.copy(spaceId = "invalid")).first()
                )
                .failure
                .kind,
        )
        f.base.deny()
        assertEquals(AppResult.Failed(f.base.denied), f.observeSignals()(f.access).first())
        assertEquals(0, f.progressObservations)
        assertTrue(f.replacements.isEmpty())
    }
}
