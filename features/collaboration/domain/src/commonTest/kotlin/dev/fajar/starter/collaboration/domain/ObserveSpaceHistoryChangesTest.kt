@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveSpaceHistoryChangesTest {
    @Test
    fun knownFinalDenialPublishesWithoutWaitingForAnotherSessionRead() = runTest {
        val f = SpaceHistorySignalFixture()
        f.currentGate = {
            AppResult.Success(CollaborationSpaceDirectoryAdmission(f.base.revision, f.base.denied))
        }
        f.beforeCurrent = { error("A known denial must not wait for unrelated session storage") }
        assertEquals(AppResult.Failed(f.base.denied), f.observe()(f.access).first())
        assertEquals(1, f.observations)
        assertEquals(1, f.stops)
    }

    @Test
    fun offlineSignalAndTokenRotationKeepOneLocalSubscriptionWithoutScanning() = runTest {
        val f = SpaceHistorySignalFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        f.base.native.setup.beforeWorkspace = { error("No selected company") }
        val values = mutableListOf<AppResult<String?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access).toList(values)
            }
        runCurrent()
        assertEquals<List<AppResult<String?>>>(listOf(AppResult.Success(f.signal)), values)
        f.base.identity.value =
            AppResult.Success(
                f.base.native.session.copy(
                    tokens = f.base.native.session.tokens.copy(accessToken = "rotated")
                )
            )
        runCurrent()
        assertEquals(1, f.observations)
        assertEquals(1, values.size)
        assertEquals(0, f.base.native.pageCalls)
        job.cancelAndJoin()
        assertEquals(1, f.stops)
    }

    @Test
    fun replacingAccountSessionOrAudienceRetiresTheOwnedSignalReader() = runTest {
        for (kind in 0..3) {
            val f = SpaceHistorySignalFixture()
            val values = mutableListOf<AppResult<String?>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access).toList(values)
                }
            runCurrent()
            f.base.identity.value =
                AppResult.Success(
                    when (kind) {
                        0 -> null
                        1 -> f.base.native.session.copy(id = "replacement")
                        2 ->
                            f.base.native.session.copy(
                                user =
                                    f.base.native.session.user.copy(
                                        id = f.base.native.contact.accountId
                                    )
                            )
                        else ->
                            f.base.native.session.copy(
                                binding =
                                    f.base.native.session.binding.copy(
                                        audience = "https://foreign.example.test/"
                                    )
                            )
                    }
                )
            runCurrent()
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(values.last()).failure.kind,
            )
            assertEquals(1, f.stops)
            f.signals.value = AppResult.Success(f.next)
            runCurrent()
            assertEquals(2, values.size)
            job.cancelAndJoin()
        }
    }

    @Test
    fun denialSurvivesFailedAdmissionAndOnlyAnAdmittedGateReopensSignals() = runTest {
        val f = SpaceHistorySignalFixture()
        val values = mutableListOf<AppResult<String?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access).toList(values)
            }
        runCurrent()
        f.base.deny()
        runCurrent()
        assertEquals(AppResult.Failed(f.base.denied), values.last())
        f.base.gate.value = AppResult.Failed(f.base.storageFailure)
        runCurrent()
        assertEquals(AppResult.Failed(f.base.denied), values.last())
        assertEquals(1, f.observations)
        f.signals.value = AppResult.Success(f.next)
        f.base.gate.value = AppResult.Success(f.base.open)
        runCurrent()
        assertEquals(AppResult.Success(f.next), values.last())
        assertEquals(2, f.observations)
        assertEquals(1, f.stops)
        job.cancelAndJoin()
        assertEquals(2, f.stops)
    }

    @Test
    fun aSlowConsumerCannotReceiveQueuedSignalsAfterRevocation() = runTest {
        for (deny in listOf(false, true)) {
            val f = SpaceHistorySignalFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val values = mutableListOf<AppResult<String?>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access).collect {
                        values += it
                        if (values.size == 1) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                }
            entered.await()
            f.signals.value = AppResult.Success(f.next)
            runCurrent()
            if (deny) f.base.deny() else f.base.identity.value = AppResult.Success(null)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(2, values.size)
            assertIs<AppResult.Failed>(values.last())
            job.cancelAndJoin()
        }
    }

    @Test
    fun denialJoinsPendingSignalWorkWithoutStartingAReplacementRead() = runTest {
        val f = SpaceHistorySignalFixture()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        f.source = {
            flow {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
        }
        val values = mutableListOf<AppResult<String?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access).toList(values)
            }
        entered.await()
        f.base.deny()
        runCurrent()
        stopped.await()
        assertEquals<List<AppResult<String?>>>(listOf(AppResult.Failed(f.base.denied)), values)
        assertEquals(1, f.observations)
        assertEquals(1, f.stops)
        job.cancelAndJoin()
    }

    @Test
    fun malformedSignalAndCompletedFailureDoNotBecomeEmptyOrAutomaticRetries() = runTest {
        val f = SpaceHistorySignalFixture()
        f.signals.value = AppResult.Success("malformed")
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(f.observe()(f.access).first()).failure.kind,
        )
        f.source = { flowOf(AppResult.Failed(f.base.storageFailure)) }
        val values = mutableListOf<AppResult<String?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access).toList(values)
            }
        runCurrent()
        assertEquals<List<AppResult<String?>>>(
            listOf(AppResult.Failed(f.base.storageFailure)),
            values,
        )
        val subscriptions = f.observations
        f.source = { flowOf(AppResult.Success(f.signal)) }
        f.base.identity.value =
            AppResult.Success(
                f.base.native.session.copy(
                    tokens = f.base.native.session.tokens.copy(accessToken = "rotated")
                )
            )
        runCurrent()
        assertEquals(subscriptions, f.observations)
        job.cancelAndJoin()
        assertEquals(AppResult.Success(f.signal), f.observe()(f.access).first())
    }

    @Test
    fun invalidOwnerAndMalformedAdmissionCannotOpenMetadataObservation() = runTest {
        val f = SpaceHistorySignalFixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.observe()(f.access.copy(spaceId = "invalid")).first())
                .failure
                .kind,
        )
        f.base.gate.value =
            AppResult.Success(CollaborationSpaceDirectoryAdmission(null, f.base.denied))
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(f.observe()(f.access).first()).failure.kind,
        )
        f.base.gate.value = AppResult.Success(f.base.open)
        f.base.identity.value = AppResult.Failed(f.base.storageFailure)
        assertEquals(AppResult.Failed(f.base.storageFailure), f.observe()(f.access).first())
        assertEquals(0, f.observations)
    }
}
