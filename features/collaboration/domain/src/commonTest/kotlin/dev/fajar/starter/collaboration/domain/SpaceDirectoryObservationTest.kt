@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class SpaceDirectoryObservationTest {
    @Test
    fun expiredCredentialsAndCompanySelectionDoNotHideSavedFactsOrAcquireNetwork() = runTest {
        val f = SpaceDirectoryUseCaseFixture()
        f.native.setup.now += 365.days
        f.native.setup.workspace = null
        f.native.setup.beforeWorkspace = { error("No company dependency") }
        f.native.onPage = { error("No implicit remote acquisition") }
        val values = mutableListOf<AppResult<CollaborationSpaceDirectorySnapshot>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access).toList(values)
            }
        runCurrent()
        assertEquals(listOf(f.facts.value), values)
        f.identity.value =
            AppResult.Success(
                f.native.session.copy(
                    tokens = f.native.session.tokens.copy(accessToken = "rotated"),
                    binding = f.native.session.binding.copy(serverId = f.native.contact.accountId),
                )
            )
        runCurrent()
        assertEquals(1, f.observations)
        assertEquals(1, values.size)
        assertEquals(0, f.native.pageCalls)
        job.cancelAndJoin()
        assertEquals(1, f.observationStops)
    }

    @Test
    fun accountSessionAndAudienceReplacementCancelOldFacts() = runTest {
        for (replacement in 0..3) {
            val f = SpaceDirectoryUseCaseFixture()
            val values = mutableListOf<AppResult<CollaborationSpaceDirectorySnapshot>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access).toList(values)
                }
            runCurrent()
            f.identity.value =
                AppResult.Success(
                    when (replacement) {
                        0 -> null
                        1 -> f.native.session.copy(id = "new-local-session")
                        2 ->
                            f.native.session.copy(
                                user = f.native.session.user.copy(id = f.native.contact.accountId)
                            )
                        else ->
                            f.native.session.copy(
                                binding =
                                    f.native.session.binding.copy(
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
            assertEquals(1, f.observationStops)
            f.facts.value =
                AppResult.Success(CollaborationSpaceDirectorySnapshot(null, emptyList()))
            runCurrent()
            assertIs<AppResult.Failed>(values.last())
            job.cancelAndJoin()
        }
    }

    @Test
    fun denialCancelsPendingFactsAndFailedCleanupCannotReopenThem() = runTest {
        val f = SpaceDirectoryUseCaseFixture()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        f.readFacts = {
            flow {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceDirectorySnapshot>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access).toList(values)
            }
        entered.await()
        f.afterRestrict = { AppResult.Failed(f.storageFailure) }
        assertIs<AppResult.Failed>(f.directory.restrict(f.access, f.denied))
        runCurrent()
        stopped.await()
        assertEquals(f.denied, assertIs<AppResult.Failed>(values.single()).failure)
        f.readFacts = { f.facts }
        f.native.onPage = { AppResult.Failed(f.unavailable) }
        assertEquals(f.denied, assertIs<AppResult.Failed>(f.refresh()(f.owner)).failure)
        runCurrent()
        assertEquals(1, f.observations)
        f.native.onPage = { AppResult.Success(f.native.page) }
        assertEquals(AppResult.Success(true), f.refresh()(f.owner))
        runCurrent()
        assertEquals(f.facts.value, values.last())
        assertEquals(2, f.observations)
        job.cancelAndJoin()
    }

    @Test
    fun malformedAdmissionAndSessionReadFailureNeverOpenFacts() = runTest {
        val f = SpaceDirectoryUseCaseFixture()
        for (gate in
            listOf(
                CollaborationSpaceDirectoryAdmission(null, f.denied),
                CollaborationSpaceDirectoryAdmission(f.revision, null),
                CollaborationSpaceDirectoryAdmission(f.revision, f.unavailable),
            )) {
            f.gate.value = AppResult.Success(gate)
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.observe()(f.access).first()).failure.kind,
            )
        }
        f.gate.value = AppResult.Success(f.open)
        f.identity.value = AppResult.Failed(f.storageFailure)
        assertEquals(AppResult.Failed(f.storageFailure), f.observe()(f.access).first())
        assertEquals(0, f.observations)
    }

    @Test
    fun completedFailedSourceReopensOnlyOnAnExplicitNewSubscription() = runTest {
        val f = SpaceDirectoryUseCaseFixture()
        f.readFacts = { flowOf(AppResult.Failed(f.storageFailure)) }
        val values = mutableListOf<AppResult<CollaborationSpaceDirectorySnapshot>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access).toList(values)
            }
        runCurrent()
        assertEquals(f.storageFailure, assertIs<AppResult.Failed>(values.single()).failure)
        f.readFacts = { f.facts }
        f.identity.value =
            AppResult.Success(
                f.native.session.copy(tokens = f.native.session.tokens.copy(accessToken = "new"))
            )
        runCurrent()
        assertEquals(1, f.observations)
        job.cancelAndJoin()
        assertEquals(f.facts.value, f.observe()(f.access).first())
        assertEquals(2, f.observations)
    }

    @Test
    fun replacingOwnerOrAdmissionWhileConsumerIsSlowDiscardsQueuedOldFacts() = runTest {
        for (deny in listOf(false, true)) {
            val f = SpaceDirectoryUseCaseFixture()
            val first = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val values = mutableListOf<AppResult<CollaborationSpaceDirectorySnapshot>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access).collect {
                        values += it
                        if (values.size == 1) {
                            first.complete(Unit)
                            resume.await()
                        }
                    }
                }
            first.await()
            f.facts.value =
                AppResult.Success(CollaborationSpaceDirectorySnapshot(null, emptyList()))
            runCurrent()
            if (deny) f.deny() else f.identity.value = AppResult.Success(null)
            runCurrent()
            resume.complete(Unit)
            runCurrent()
            assertEquals(2, values.size, "deny=$deny must discard queued old facts")
            assertIs<AppResult.Failed>(values.last())
            job.cancelAndJoin()
        }
    }
}
