@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveSpaceThreadHistoryAdmissionTest {
    @Test
    fun deniedAndMalformedAdmissionNeverReadTheBinding() = runTest {
        val f = SpaceThreadHistoryFixture()
        f.base.deny()
        assertEquals(AppResult.Failed(f.base.denied), f.observe()(f.access, f.id).first())
        f.base.gate.value =
            AppResult.Success(CollaborationSpaceDirectoryAdmission(null, f.base.denied))
        assertIs<AppResult.Failed>(f.observe()(f.access, f.id).first())
        assertEquals(0, f.room.bindingReads)
    }

    @Test
    fun confirmedDenialSurvivesFailedCleanupMalformedGatesAndFailedSessionRecovery() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SpaceThreadHistoryFixture(legacy)
            val values = mutableListOf<AppResult<CollaborationSpaceThreadHistory>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.id).toList(values)
                }
            runCurrent()
            f.base.afterRestrict = { AppResult.Failed(f.base.storageFailure) }
            assertIs<AppResult.Failed>(f.directory.restrict(f.access, f.base.denied))
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
            }
            f.identity.value = AppResult.Failed(f.base.storageFailure)
            runCurrent()
            f.base.gate.value = AppResult.Failed(f.base.unavailable)
            f.identity.value = AppResult.Success(f.native.session)
            runCurrent()
            assertEquals(AppResult.Failed(f.base.denied), values.last())
            assertEquals(1, f.room.bindingReads)
            f.base.gate.value = AppResult.Success(f.base.open)
            runCurrent()
            assertIs<AppResult.Success<*>>(values.last())
            assertEquals(2, f.room.bindingReads)
            job.cancelAndJoin()
        }
    }

    @Test
    fun replacementCancelsAndJoinsHeldBindingBeforePublishingAnotherAccount() = runTest {
        val f = SpaceThreadHistoryFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val retired = CompletableDeferred<Unit>()
        f.room.beforeBindingRead = {
            entered.complete(Unit)
            try {
                withContext(NonCancellable) { release.await() }
            } finally {
                retired.complete(Unit)
            }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceThreadHistory>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.id).toList(values)
            }
        entered.await()
        f.identity.value = AppResult.Success(f.native.session.copy(id = "replacement"))
        runCurrent()
        assertTrue(values.isEmpty())
        release.complete(Unit)
        retired.await()
        runCurrent()
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.single()).failure.kind,
        )
        assertEquals(0, f.spaceReads + f.originalReads)
        job.cancelAndJoin()
    }

    @Test
    fun currentSessionReplacementAfterBindingReadRejectsItBeforeTheObservedSessionCatchesUp() =
        runTest {
            val f = SpaceThreadHistoryFixture()
            f.room.beforeBindingRead = { f.currentIdentity = { AppResult.Success(null) } }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.observe()(f.access, f.id).first()).failure.kind,
            )
            assertEquals(1, f.room.bindingReads)
            assertEquals(0, f.spaceReads)
        }

    @Test
    fun publicationDenialLatchesEvenBeforeItsFlowEmissionAndFailedReadsCannotReopenIt() = runTest {
        val f = SpaceThreadHistoryFixture()
        var checks = 0
        val blocked = CollaborationSpaceDirectoryAdmission(f.base.revision, f.base.denied)
        f.currentAdmission = {
            checks++
            if (checks >= 3) AppResult.Success(blocked) else AppResult.Success(f.base.open)
        }
        val values = mutableListOf<AppResult<CollaborationSpaceThreadHistory>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.id).toList(values)
            }
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationSpaceThreadHistory>>(AppResult.Failed(f.base.denied)),
            values,
        )
        f.currentAdmission = { AppResult.Failed(f.base.storageFailure) }
        f.facts.value = AppResult.Success(f.page().copy(revision = "changed"))
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationSpaceThreadHistory>>(AppResult.Failed(f.base.denied)),
            values,
        )
        f.currentAdmission = { AppResult.Success(f.base.open) }
        f.facts.value = AppResult.Success(f.page())
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationSpaceThreadHistory>>(AppResult.Failed(f.base.denied)),
            values,
        )
        f.base.gate.value = AppResult.Success(blocked)
        runCurrent()
        f.base.gate.value = AppResult.Success(f.base.open)
        runCurrent()
        assertIs<AppResult.Success<*>>(values.last())
        job.cancelAndJoin()
    }

    @Test
    fun denialStopsPendingHistoryAndLateContentCannotLeakAfterIt() = runTest {
        val f = SpaceThreadHistoryFixture(true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.readFacts = {
            flow {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                emit(AppResult.Success(f.page()))
            }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceThreadHistory>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.id).toList(values)
            }
        entered.await()
        f.base.deny()
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationSpaceThreadHistory>>(AppResult.Failed(f.base.denied)),
            values,
        )
        assertEquals(1, f.stops)
        job.cancelAndJoin()
    }

    @Test
    fun slowConsumerReceivesNoQueuedPrivatePageAfterRevocationOrLogout() = runTest {
        for (deny in listOf(false, true)) {
            val f = SpaceThreadHistoryFixture()
            val first = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val values = mutableListOf<AppResult<CollaborationSpaceThreadHistory>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.id).collect {
                        values += it
                        if (values.size == 1) {
                            first.complete(Unit)
                            release.await()
                        }
                    }
                }
            first.await()
            f.facts.value = AppResult.Success(f.page().copy(revision = "second"))
            runCurrent()
            if (deny) f.base.deny() else f.identity.value = AppResult.Success(null)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(2, values.size)
            assertIs<AppResult.Failed>(values.last())
            job.cancelAndJoin()
        }
    }

    @Test
    fun accountSessionAudienceAndLogoutEachRetireTheOldSubscription() = runTest {
        val f = SpaceThreadHistoryFixture(true)
        val replacements =
            listOf(
                null,
                f.native.session.copy(id = "other"),
                f.native.session.copy(
                    user = f.native.session.user.copy(id = f.native.contact.accountId)
                ),
                f.native.session.copy(
                    binding =
                        f.native.session.binding.copy(audience = "https://other.example.test/")
                ),
            )
        for (replacement in replacements) {
            f.identity.value = AppResult.Success(f.native.session)
            val values = mutableListOf<AppResult<CollaborationSpaceThreadHistory>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.id).toList(values)
                }
            runCurrent()
            val previousStops = f.stops
            f.identity.value = AppResult.Success(replacement)
            runCurrent()
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(values.last()).failure.kind,
            )
            assertEquals(previousStops + 1, f.stops)
            job.cancelAndJoin()
        }
    }
}
