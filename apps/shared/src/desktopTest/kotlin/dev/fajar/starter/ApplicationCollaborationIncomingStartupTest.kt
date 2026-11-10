@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationState
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceIncomingScanReason
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.IncomingStartupFixture
import dev.fajar.starter.fixtures.recoveryId
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ApplicationCollaborationIncomingStartupTest {
    @Test
    fun foregroundEntryRequestsOnceWithoutEagerBackgroundObservation() = runTest {
        val f = IncomingStartupFixture(testScheduler)
        assertFalse(f.states.isInitialized())
        assertTrue(f.requests.isEmpty())
        val work = f.startup.start(backgroundScope)
        runCurrent()
        repeat(5) {
            f.changes.emit(AppResult.Success(f.ready))
            runCurrent()
        }
        assertEquals(1, f.requests.size)
        assertEquals(1, f.subscriptions)
        work.cancelAndJoin()
        assertEquals(0, f.subscriptions)
    }

    @Test
    fun pendingParentDoesNotCreateDemandUntilOwnershipIsPublished() = runTest {
        val f = IncomingStartupFixture(testScheduler)
        f.base.states.value = ApplicationCollaborationState.Pending
        f.startup.start(backgroundScope)
        runCurrent()
        assertTrue(f.requests.isEmpty())
        f.base.states.value = f.base.publication()
        runCurrent()
        assertEquals(1, f.requests.size)
    }

    @Test
    fun localReadFailureAfterConfirmationDoesNotCreateAnotherScan() = runTest {
        val f = IncomingStartupFixture(testScheduler)
        f.startup.start(backgroundScope)
        runCurrent()
        f.changes.emit(f.failure)
        runCurrent()
        f.changes.emit(AppResult.Success(f.ready))
        runCurrent()
        assertEquals(1, f.requests.size)
        assertEquals(1, f.ids)
    }

    @Test
    fun uncertainFalseFailedThrownAndTimedOutRequestsKeepOneIdentity() = runTest {
        for (outcome in listOf("false", "failed", "thrown", "timeout")) {
            val f = IncomingStartupFixture(testScheduler)
            f.request = {
                when (outcome) {
                    "false" -> AppResult.Success(false)
                    "failed" -> f.failure
                    "thrown" -> error("Fixture storage exception")
                    else -> awaitCancellation()
                }
            }
            val work = f.startup.start(backgroundScope)
            runCurrent()
            if (outcome == "timeout") {
                advanceTimeBy(6_001)
                runCurrent()
            }
            f.changes.emit(f.failure)
            runCurrent()
            f.request = { AppResult.Success(true) }
            f.changes.emit(AppResult.Success(f.ready))
            runCurrent()
            assertEquals(2, f.requests.size, outcome)
            assertEquals(f.requests[0], f.requests[1], outcome)
            assertEquals(1, f.ids, outcome)
            work.cancelAndJoin()
        }
    }

    @Test
    fun rootRemovalCancelsPendingRequestAndReadmissionUsesANewIdentity() = runTest {
        val f = IncomingStartupFixture(testScheduler)
        var cancelled = false
        f.request = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        f.startup.start(backgroundScope)
        runCurrent()
        f.changes.emit(AppResult.Success(null))
        runCurrent()
        assertTrue(cancelled)
        f.request = { AppResult.Success(true) }
        f.changes.emit(AppResult.Success(f.ready))
        runCurrent()
        assertEquals(2, f.requests.size)
        assertNotEquals(f.requests[0].second, f.requests[1].second)
    }

    @Test
    fun confirmedDenialRetiresCompletedStartupBeforeReadmission() = runTest {
        for (kind in
            listOf(FailureKind.Unauthorized, FailureKind.AccessDenied, FailureKind.Permission)) {
            val f = IncomingStartupFixture(testScheduler)
            val work = f.startup.start(backgroundScope)
            runCurrent()
            f.changes.emit(AppResult.Failed(Failure(kind, "Fixture denial")))
            runCurrent()
            f.changes.emit(AppResult.Success(f.ready))
            runCurrent()
            assertEquals(2, f.requests.size)
            assertEquals(2, f.ids)
            work.cancelAndJoin()
        }
    }

    @Test
    fun parentReplacementCancelsOldObservationAndPendingWrite() = runTest {
        val f = IncomingStartupFixture(testScheduler)
        var cancelled = false
        f.request = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        f.startup.start(backgroundScope)
        runCurrent()
        val next = f.base.parent.copy(sessionId = "replacement")
        f.base.states.value = f.base.publication(next)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(listOf(f.base.parent, next), f.parents)
        assertEquals(1, f.requests.size)
        f.request = { AppResult.Success(true) }
        f.changes.emit(AppResult.Success(f.ready.copy(owner = f.ready.owner.copy(parent = next))))
        runCurrent()
        assertEquals("replacement", f.requests.last().first.sessionId)
        assertEquals(1, f.subscriptions)
    }

    @Test
    fun lateNoncancellableReplyCannotConfirmTheReplacementParent() = runTest {
        val f = IncomingStartupFixture(testScheduler)
        val release = CompletableDeferred<Unit>()
        f.request = {
            withContext(NonCancellable) {
                release.await()
                AppResult.Success(true)
            }
        }
        f.startup.start(backgroundScope)
        runCurrent()
        val next = f.base.parent.copy(sessionId = "replacement")
        f.base.states.value = f.base.publication(next)
        runCurrent()
        f.request = { AppResult.Success(true) }
        f.changes.emit(AppResult.Success(f.ready.copy(owner = f.ready.owner.copy(parent = next))))
        release.complete(Unit)
        runCurrent()
        assertEquals(2, f.requests.size)
        assertEquals("replacement", f.requests.last().first.sessionId)
        assertNotEquals(f.requests[0].second, f.requests[1].second)
    }

    @Test
    fun disposingForegroundCancelsTheRequestAndAllSubscriptions() = runTest {
        val f = IncomingStartupFixture(testScheduler)
        var cancelled = false
        f.request = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val work = f.startup.start(backgroundScope)
        runCurrent()
        work.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals(0, f.subscriptions)
        f.changes.emit(AppResult.Success(f.ready.copy(operationId = recoveryId(888))))
        runCurrent()
        assertEquals(1, f.requests.size)
    }

    @Test
    fun restartingForegroundRequestsRecoveryWithoutReusingAnOldCollector() = runTest {
        val f = IncomingStartupFixture(testScheduler)
        val first = f.startup.start(backgroundScope)
        runCurrent()
        first.cancelAndJoin()
        val second = f.startup.start(backgroundScope)
        runCurrent()
        assertEquals(2, f.requests.size)
        assertEquals(f.requests[0], f.requests[1])
        assertEquals(
            listOf(
                CollaborationSpaceIncomingScanReason.Startup,
                CollaborationSpaceIncomingScanReason.Resume,
            ),
            f.reasons,
        )
        assertEquals(1, f.subscriptions)
        second.cancelAndJoin()
        assertEquals(0, f.subscriptions)
    }

    @Test
    fun unfinishedStartupKeepsItsIdentityAcrossForegroundDisposal() = runTest {
        val f = IncomingStartupFixture(testScheduler)
        f.request = { awaitCancellation() }
        val first = f.startup.start(backgroundScope)
        runCurrent()
        first.cancelAndJoin()
        f.request = { AppResult.Success(true) }
        f.startup.start(backgroundScope)
        runCurrent()
        assertEquals(2, f.requests.size)
        assertEquals(f.requests[0], f.requests[1])
        assertEquals(
            listOf(
                CollaborationSpaceIncomingScanReason.Startup,
                CollaborationSpaceIncomingScanReason.Startup,
            ),
            f.reasons,
        )
    }

    @Test
    fun differentParentWhileStoppedCannotReuseThePreviousBootstrap() = runTest {
        val f = IncomingStartupFixture(testScheduler)
        val first = f.startup.start(backgroundScope)
        runCurrent()
        first.cancelAndJoin()
        val parent = f.base.parent.copy(sessionId = "replacement")
        f.base.states.value = f.base.publication(parent)
        f.changes.emit(AppResult.Success(f.ready.copy(owner = f.ready.owner.copy(parent = parent))))
        f.startup.start(backgroundScope)
        runCurrent()
        assertEquals(2, f.requests.size)
        assertNotEquals(f.requests[0], f.requests[1])
        assertEquals(
            listOf(
                CollaborationSpaceIncomingScanReason.Startup,
                CollaborationSpaceIncomingScanReason.Startup,
            ),
            f.reasons,
        )
    }

    @Test
    fun overlappingHostEntriesWaitForPreviousOwnershipToEnd() = runTest {
        val f = IncomingStartupFixture(testScheduler)
        val first = f.startup.start(backgroundScope)
        val second = f.startup.start(backgroundScope)
        runCurrent()
        assertEquals(1, f.requests.size)
        assertEquals(1, f.subscriptions)
        first.cancelAndJoin()
        runCurrent()
        assertEquals(2, f.requests.size)
        assertEquals(1, f.subscriptions)
        second.cancelAndJoin()
    }

    @Test
    fun changedBootstrapCreatesOneRequestForItsNewDevice() = runTest {
        val f = IncomingStartupFixture(testScheduler, AppPlatform.Ios)
        f.startup.start(backgroundScope)
        runCurrent()
        val next = f.ready.copy(operationId = recoveryId(888), deviceId = recoveryId(889))
        repeat(3) {
            f.changes.emit(AppResult.Success(next))
            runCurrent()
        }
        assertEquals(2, f.requests.size)
        assertEquals(2, f.ids)
    }

    @Test
    fun unsupportedPlatformsNeverResolveParentOrOpenStorage() = runTest {
        for (platform in
            AppPlatform.entries.filter { it !in setOf(AppPlatform.Android, AppPlatform.Ios) }) {
            val f = IncomingStartupFixture(testScheduler, platform)
            f.startup.start(backgroundScope).join()
            assertFalse(f.states.isInitialized())
            assertEquals(0, f.subscriptions)
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test
    fun subscriptionFailureEndsItsPassWithoutARetryLoop() = runTest {
        val f = IncomingStartupFixture(testScheduler)
        f.beforeObserve = { error("Fixture storage subscription failed") }
        f.startup.start(backgroundScope).join()
        advanceTimeBy(60_000)
        assertEquals(0, f.subscriptions)
        assertTrue(f.requests.isEmpty())
        f.beforeObserve = {}
        f.startup.start(backgroundScope)
        runCurrent()
        assertEquals(1, f.requests.size)
    }
}
