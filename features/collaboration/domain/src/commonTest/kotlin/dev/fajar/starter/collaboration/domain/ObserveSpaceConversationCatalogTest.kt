@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveSpaceConversationCatalogTest {
    @Test
    fun knownFinalDenialPublishesWithoutWaitingForAnotherSessionRead() = runTest {
        val f = SpaceCatalogUseCaseFixture()
        f.currentGate = {
            AppResult.Success(CollaborationSpaceDirectoryAdmission(f.base.revision, f.base.denied))
        }
        f.beforeCurrent = { error("A known denial must not wait for unrelated session storage") }
        assertEquals(AppResult.Failed(f.base.denied), f.observe()(f.access).first())
        assertEquals(1, f.observations)
        assertEquals(1, f.stops)
    }

    @Test
    fun offlineVersionAndTokenRotationKeepOneLocalSubscriptionWithoutScanning() = runTest {
        val f = SpaceCatalogUseCaseFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        f.base.native.setup.beforeWorkspace = { error("No selected company") }
        val values = mutableListOf<AppResult<CollaborationSpaceConversationCatalogVersion?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access).toList(values)
            }
        runCurrent()
        assertEquals<List<AppResult<CollaborationSpaceConversationCatalogVersion?>>>(
            listOf(AppResult.Success(f.version)),
            values,
        )
        f.base.identity.value =
            AppResult.Success(
                f.base.native.session.copy(
                    tokens = f.base.native.session.tokens.copy(accessToken = "rotated")
                )
            )
        runCurrent()
        assertEquals(1, f.observations)
        assertEquals(1, values.size)
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.base.native.pageCalls)
        job.cancelAndJoin()
        assertEquals(1, f.stops)
    }

    @Test
    fun replacingAccountSessionOrAudienceRetiresTheOwnedVersionReader() = runTest {
        for (kind in 0..3) {
            val f = SpaceCatalogUseCaseFixture()
            val values = mutableListOf<AppResult<CollaborationSpaceConversationCatalogVersion?>>()
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
                                        id = f.conversation.secondAccountId
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
            f.versions.value = AppResult.Success(f.version.copy(revision = 2))
            runCurrent()
            assertEquals(2, values.size)
            job.cancelAndJoin()
        }
    }

    @Test
    fun denialSurvivesFailedAdmissionAndOnlyAnAdmittedGateReopensVersions() = runTest {
        val f = SpaceCatalogUseCaseFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceConversationCatalogVersion?>>()
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
        f.versions.value = AppResult.Success(f.version.copy(revision = 2))
        f.base.gate.value = AppResult.Success(f.base.open)
        runCurrent()
        assertEquals(AppResult.Success(f.version.copy(revision = 2)), values.last())
        assertEquals(2, f.observations)
        assertEquals(1, f.stops)
        job.cancelAndJoin()
        assertEquals(2, f.stops)
    }

    @Test
    fun aSlowConsumerCannotReceiveQueuedVersionsAfterRevocation() = runTest {
        for (deny in listOf(false, true)) {
            val f = SpaceCatalogUseCaseFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val values = mutableListOf<AppResult<CollaborationSpaceConversationCatalogVersion?>>()
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
            f.versions.value = AppResult.Success(f.version.copy(revision = 2))
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
    fun denialJoinsPendingVersionWorkWithoutStartingAReplacementRead() = runTest {
        val f = SpaceCatalogUseCaseFixture()
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
        val values = mutableListOf<AppResult<CollaborationSpaceConversationCatalogVersion?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access).toList(values)
            }
        entered.await()
        f.base.deny()
        runCurrent()
        stopped.await()
        assertEquals<List<AppResult<CollaborationSpaceConversationCatalogVersion?>>>(
            listOf(AppResult.Failed(f.base.denied)),
            values,
        )
        assertEquals(1, f.observations)
        assertEquals(1, f.stops)
        assertTrue(f.requests.isEmpty())
        job.cancelAndJoin()
    }

    @Test
    fun malformedVersionAndCompletedFailureDoNotBecomeEmptyOrAutomaticRetries() = runTest {
        val f = SpaceCatalogUseCaseFixture()
        f.versions.value = AppResult.Success(f.version.copy(revision = -1))
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(f.observe()(f.access).first()).failure.kind,
        )
        f.source = { flowOf(AppResult.Failed(f.base.storageFailure)) }
        val values = mutableListOf<AppResult<CollaborationSpaceConversationCatalogVersion?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access).toList(values)
            }
        runCurrent()
        assertEquals<List<AppResult<CollaborationSpaceConversationCatalogVersion?>>>(
            listOf(AppResult.Failed(f.base.storageFailure)),
            values,
        )
        val subscriptions = f.observations
        f.source = { flowOf(AppResult.Success(f.version)) }
        f.base.identity.value =
            AppResult.Success(
                f.base.native.session.copy(
                    tokens = f.base.native.session.tokens.copy(accessToken = "rotated")
                )
            )
        runCurrent()
        assertEquals(subscriptions, f.observations)
        job.cancelAndJoin()
        assertEquals(AppResult.Success(f.version), f.observe()(f.access).first())
    }

    @Test
    fun invalidOwnerAndMalformedAdmissionCannotOpenMetadataObservation() = runTest {
        val f = SpaceCatalogUseCaseFixture()
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
