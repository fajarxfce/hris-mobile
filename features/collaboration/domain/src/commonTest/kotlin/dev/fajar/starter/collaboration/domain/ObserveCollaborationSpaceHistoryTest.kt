@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveCollaborationSpaceHistoryTest {
    @Test
    fun transientSessionFailureCannotForgetConfirmedDenialWhenAdmissionStillFails() = runTest {
        val f = SpaceHistoryUseCaseFixture()
        val values = mutableListOf<AppResult<CollaborationHistoryPage>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.room.conversation.id).toList(values)
            }
        runCurrent()
        f.base.deny()
        runCurrent()
        f.identity.value = AppResult.Failed(f.base.storageFailure)
        runCurrent()
        assertEquals(AppResult.Failed(f.base.denied), values.last())
        f.base.gate.value = AppResult.Failed(f.base.unavailable)
        f.identity.value = AppResult.Success(f.native.session)
        runCurrent()
        assertEquals(AppResult.Failed(f.base.denied), values.last())
        assertEquals(1, f.observations)
        f.base.gate.value = AppResult.Success(f.base.open)
        runCurrent()
        assertEquals(f.facts.value, values.last())
        assertEquals(2, f.observations)
        job.cancelAndJoin()
    }

    @Test
    fun ownedFactsAppearOfflineAndIdenticalEmissionsOrCredentialRotationDoNotRestartStorage() =
        runTest {
            val f = SpaceHistoryUseCaseFixture()
            f.native.setup.now += 1000.days
            f.native.setup.workspace = null
            f.facts.value = AppResult.Success(f.page())
            val values = mutableListOf<AppResult<CollaborationHistoryPage>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.room.conversation.id).toList(values)
                }
            runCurrent()
            assertEquals(listOf(f.facts.value), values)
            f.identity.value =
                AppResult.Success(
                    f.native.session.copy(
                        tokens = f.native.session.tokens.copy(accessToken = "rotated")
                    )
                )
            f.facts.value = AppResult.Success(f.page())
            runCurrent()
            assertEquals(1, f.observations)
            assertEquals(1, values.size)
            assertTrue(f.writes.isEmpty() && f.schedules.isEmpty())
            job.cancelAndJoin()
            assertEquals(1, f.stops)
        }

    @Test
    fun logoutOrLocalSessionAccountAndAudienceReplacementCancelsOldStorage() = runTest {
        for (replacement in 0..3) {
            val f = SpaceHistoryUseCaseFixture()
            val values = mutableListOf<AppResult<CollaborationHistoryPage>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.room.conversation.id).toList(values)
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
            assertEquals(1, f.stops)
            f.facts.value = AppResult.Success(f.page())
            runCurrent()
            assertIs<AppResult.Failed>(values.last())
            job.cancelAndJoin()
        }
    }

    @Test
    fun denialSurvivesFailedReadsAndMalformedGatesUntilExplicitAdmission() = runTest {
        val f = SpaceHistoryUseCaseFixture()
        val values = mutableListOf<AppResult<CollaborationHistoryPage>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.room.conversation.id).toList(values)
            }
        runCurrent()
        f.base.deny()
        runCurrent()
        assertEquals(1, f.stops)
        for (gate in
            listOf(
                AppResult.Failed(f.base.storageFailure),
                AppResult.Success(CollaborationSpaceDirectoryAdmission(null, f.base.denied)),
            )) {
            f.base.gate.value = gate
            runCurrent()
            assertEquals(AppResult.Failed(f.base.denied), values.last())
            assertEquals(1, f.observations)
        }
        f.base.gate.value = AppResult.Success(f.base.open)
        runCurrent()
        assertEquals(f.facts.value, values.last())
        assertEquals(2, f.observations)
        job.cancelAndJoin()
    }

    @Test
    fun deniedOrMalformedAdmissionAndInvalidConversationNeverSubscribeToFacts() = runTest {
        val f = SpaceHistoryUseCaseFixture()
        assertIs<AppResult.Failed>(f.observe()(f.access, "invalid").first())
        f.base.deny()
        assertEquals(
            AppResult.Failed(f.base.denied),
            f.observe()(f.access, f.room.conversation.id).first(),
        )
        f.base.gate.value =
            AppResult.Success(CollaborationSpaceDirectoryAdmission(f.base.revision, null))
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(f.observe()(f.access, f.room.conversation.id).first())
                .failure
                .kind,
        )
        assertEquals(0, f.observations)
    }

    @Test
    fun denialCancelsSuspendedStorageAndDoesNotDependOnDeletingCiphertext() = runTest {
        val f = SpaceHistoryUseCaseFixture()
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
        val values = mutableListOf<AppResult<CollaborationHistoryPage>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.room.conversation.id).toList(values)
            }
        entered.await()
        f.base.afterRestrict = { AppResult.Failed(f.base.storageFailure) }
        assertIs<AppResult.Failed>(f.base.directory.restrict(f.access, f.base.denied))
        stopped.await()
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationHistoryPage>>(AppResult.Failed(f.base.denied)),
            values,
        )
        job.cancelAndJoin()
    }

    @Test
    fun slowConsumerCannotReceiveBufferedPrivateFactsAfterRevocation() = runTest {
        for (deny in listOf(false, true)) {
            val f = SpaceHistoryUseCaseFixture()
            val first = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val values = mutableListOf<AppResult<CollaborationHistoryPage>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.room.conversation.id).collect {
                        values += it
                        if (values.size == 1) {
                            first.complete(Unit)
                            resume.await()
                        }
                    }
                }
            first.await()
            f.facts.value = AppResult.Success(f.page())
            runCurrent()
            if (deny) f.base.deny() else f.identity.value = AppResult.Success(null)
            runCurrent()
            resume.complete(Unit)
            runCurrent()
            assertEquals(2, values.size)
            assertIs<AppResult.Failed>(values.last())
            job.cancelAndJoin()
        }
    }

    @Test
    fun completedFailedSourceHasNoAutomaticRetryLoop() = runTest {
        val f = SpaceHistoryUseCaseFixture()
        f.readFacts = { flowOf(AppResult.Failed(f.base.storageFailure)) }
        val values = mutableListOf<AppResult<CollaborationHistoryPage>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.room.conversation.id).toList(values)
            }
        runCurrent()
        advanceTimeBy(60_000)
        f.readFacts = { f.facts }
        f.identity.value =
            AppResult.Success(
                f.native.session.copy(tokens = f.native.session.tokens.copy(accessToken = "new"))
            )
        runCurrent()
        assertEquals(1, f.observations)
        assertEquals(
            listOf<AppResult<CollaborationHistoryPage>>(AppResult.Failed(f.base.storageFailure)),
            values,
        )
        job.cancelAndJoin()
        assertEquals(f.facts.value, f.observe()(f.access, f.room.conversation.id).first())
        assertEquals(2, f.observations)
    }

    @Test
    fun windowBoundsAreCheckedBeforeObservationAndValidAnchorsStayLocal() = runTest {
        val f = SpaceHistoryUseCaseFixture()
        val anchor = "0000000000000001/m-" + "a".repeat(64)
        for (window in
            listOf(
                CollaborationHistoryWindow(limit = 151),
                CollaborationHistoryWindow(limit = 0),
                CollaborationHistoryWindow(after = "bad"),
                CollaborationHistoryWindow(after = anchor, before = anchor),
                CollaborationHistoryWindow(limit = 5, overlap = 5),
            )) {
            val result =
                f.observe()(
                        f.access,
                        f.room.conversation.id,
                        window.after,
                        window.limit,
                        window.before,
                        window.overlap,
                    )
                    .first()
            assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(result).failure.kind)
        }
        assertEquals(0, f.observations)
        assertEquals(
            f.facts.value,
            f.observe()(f.access, f.room.conversation.id, after = anchor, limit = 10, overlap = 3)
                .first(),
        )
        assertEquals(
            listOf(CollaborationHistoryWindow(after = anchor, limit = 10, overlap = 3)),
            f.queries,
        )
        assertTrue(f.writes.isEmpty() && f.schedules.isEmpty())
    }
}
