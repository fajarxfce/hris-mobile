package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class AdvanceCollaborationIncomingReferencesTest {
    @Test
    fun capturedExpiredOwnershipAdvancesWithoutSelectionOrContentAcknowledgement() = runTest {
        val f = IncomingHistoryAdvanceFixture()
        f.local.setup.now += 365.days
        f.local.setup.workspace = null
        f.local.setup.beforeWorkspace = { error("No selection dependency") }
        assertEquals(AppResult.Success(Unit), f.useCase(f.scope, f.key, f.source, true))
        assertEquals(f.source, f.saved)
        assertEquals(true, f.retry)
        assertEquals(1, f.calls)
        assertEquals(0, f.local.setup.calls)
        assertEquals(0, f.active)
    }

    @Test
    fun invalidPurposePageAndPassBoundsNeverReachStorage() = runTest {
        val f = IncomingHistoryAdvanceFixture()
        val invalid =
            listOf(
                f.source.copy(progress = null),
                f.source.copy(events = f.source.events + f.source.events.first()),
                f.source.copy(progress = f.source.progress!!.copy(references = 20)),
                f.source.copy(progress = f.source.progress!!.copy(incoming = null)),
            )
        for (page in invalid) assertIs<AppResult.Failed>(f.useCase(f.scope, f.key, page, false))
        assertIs<AppResult.Failed>(
            f.useCase(
                f.scope,
                f.key.copy(purpose = CollaborationHistoryPurpose.Browse),
                f.source,
                false,
            )
        )
        assertEquals(0, f.calls)
    }

    @Test
    fun foreignOwnershipAndConfirmedDenialPreventSeekChanges() = runTest {
        val f = IncomingHistoryAdvanceFixture()
        for (access in
            listOf(
                f.access.copy(accountId = "f0000000-0000-4000-8000-000000000001"),
                f.access.copy(audience = "https://foreign.test/"),
            )) {
            assertIs<AppResult.Failed>(
                f.useCase(CollaborationLocalScope.Captured(access), f.key, f.source, false)
            )
        }
        f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
        assertEquals(AppResult.Failed(f.local.denied), f.useCase(f.scope, f.key, f.source, false))
        assertEquals(0, f.calls)
    }

    @Test
    fun cancellationStopsThePendingCommitAndDoesNotStartStorageWhenAlreadyCancelled() = runTest {
        val f = IncomingHistoryAdvanceFixture()
        val early = launch {
            currentCoroutineContext().cancel()
            f.useCase(f.scope, f.key, f.source, false)
        }
        early.join()
        assertTrue(early.isCancelled)
        assertEquals(0, f.calls)
        val entered = CompletableDeferred<Unit>()
        f.before = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val task = launch { f.useCase(f.scope, f.key, f.source, false) }
        entered.await()
        task.cancelAndJoin()
        assertEquals(0, f.active)
        assertNull(f.saved)
    }

    @Test
    fun aLostOwnerAfterCommitSuppressesSuccessWithoutErasingTheSavedAttempt() = runTest {
        val f = IncomingHistoryAdvanceFixture()
        f.after = { f.local.setup.session = null }
        assertIs<AppResult.Failed>(f.useCase(f.scope, f.key, f.source, true))
        assertEquals(f.source, f.saved)
        assertEquals(0, f.active)
    }

    @Test
    fun lateDenialWinsOverConcurrentSessionAndStorageFailuresAndCasLossRetries() = runTest {
        val f = IncomingHistoryAdvanceFixture()
        f.reject = true
        assertEquals(
            "client.collaboration_history_progress_changed",
            assertIs<AppResult.Failed>(f.useCase(f.scope, f.key, f.source, false)).failure.code,
        )
        assertNull(f.saved)
        f.failure = f.local.storage
        f.after = {
            f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
            f.local.setup.sessionFailure = f.local.storage
        }
        assertEquals(AppResult.Failed(f.local.denied), f.useCase(f.scope, f.key, f.source, false))
    }

    @Test
    fun callerMutationCannotChangeThePageAfterAdmissionSuspends() = runTest {
        val f = IncomingHistoryAdvanceFixture()
        val events = f.source.events.toMutableList()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.before = {
            entered.complete(Unit)
            release.await()
        }
        val task = async { f.useCase(f.scope, f.key, f.source.copy(events = events), false) }
        entered.await()
        events.clear()
        release.complete(Unit)
        assertEquals(AppResult.Success(Unit), task.await())
        assertEquals(f.source.events, f.saved!!.events)
        assertEquals(0, f.active)
    }
}
