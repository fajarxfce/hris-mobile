package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class CollaborationNotificationChangesTest {
    @Test
    fun ownedCompanyDoesNotRequireSelectionLiveCredentialsOrContent() = runTest {
        val f = NotificationChangesFixture()
        f.local.setup.workspace = null
        f.local.setup.now += 365.days
        assertEquals(AppResult.Success(Unit), f.observe().first())
        assertEquals(1, f.started)
        assertEquals(0, f.active)
        assertEquals(0, f.local.messageReads)
        assertEquals(0, f.local.messageObservations)
    }

    @Test
    fun repeatedAdvisoryEventsAreNotCollapsedBecauseTheirValuesAreUnit() = runTest {
        val f = NotificationChangesFixture()
        f.source = flow {
            repeat(3) { emit(AppResult.Success(Unit)) }
            awaitCancellation()
        }
        assertEquals(List(3) { AppResult.Success(Unit) }, f.observe().take(3).toList())
        assertEquals(1, f.started)
        assertEquals(0, f.active)
    }

    @Test
    fun initialDenialInvalidSelectionAndForeignParentDoNotSubscribeToSources() = runTest {
        val f = NotificationChangesFixture()
        f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
        assertEquals(AppResult.Failed(f.local.denied), f.observe().first())
        assertIs<AppResult.Failed>(f.observer(f.parent, "bad", f.conversation).first())
        assertIs<AppResult.Failed>(f.observer(f.parent, f.company, "bad").first())
        assertIs<AppResult.Failed>(
            f.observer(f.parent.copy(sessionId = "other"), f.company, f.conversation).first()
        )
        assertEquals(0, f.started)
    }

    @Test
    fun denialJoinsPendingSourceAndOnlyAnAdmittedGateResubscribes() = runTest {
        val f = NotificationChangesFixture()
        val entered = CompletableDeferred<Unit>()
        f.source = flow {
            entered.complete(Unit)
            awaitCancellation()
        }
        val values = Channel<AppResult<Unit>>(8)
        val job = launch { f.observe().collect { values.send(it) } }
        try {
            entered.await()
            f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
            assertEquals(AppResult.Failed(f.local.denied), values.receive())
            assertEquals(0, f.active)
            f.local.gateFailure = f.local.storage
            f.local.gate.value = CollaborationListAdmission("failed-attempt", f.local.denied)
            assertIs<AppResult.Failed>(values.receive())
            assertEquals(1, f.started)
            f.local.gateFailure = null
            f.source = flow {
                emit(AppResult.Success(Unit))
                awaitCancellation()
            }
            f.local.gate.value = CollaborationListAdmission("admitted", null)
            assertEquals(AppResult.Success(Unit), values.receive())
            assertEquals(2, f.started)
        } finally {
            withContext(NonCancellable) { job.cancelAndJoin() }
            values.close()
        }
        assertEquals(0, f.active)
    }

    @Test
    fun lateConfirmedDenialWinsOverSourceAndSessionFailures() = runTest {
        val f = NotificationChangesFixture()
        f.source = flow {
            // The independently buffered finite admission read already sees this denial.
            f.finiteGate = CollaborationListAdmission("buffered", f.local.denied)
            f.local.setup.sessionFailure = f.local.storage
            emit(AppResult.Failed(f.local.storage))
            awaitCancellation()
        }
        assertEquals(AppResult.Failed(f.local.denied), f.observe().first())
        assertEquals(0, f.active)
    }

    @Test
    fun anAlreadyKnownDenialIsNotDelayedByAPendingSessionRead() = runTest {
        val f = NotificationChangesFixture()
        var pendingSessionEntered = false
        f.finiteGate = CollaborationListAdmission("buffered", f.local.denied)
        f.beforeSessionRead = {
            pendingSessionEntered = true
            awaitCancellation()
        }
        assertEquals(AppResult.Failed(f.local.denied), f.observe().first())
        assertFalse(pendingSessionEntered)
        assertEquals(0, f.active)
    }

    @Test
    fun changedParentDuringFinalReadCannotReturnAnAdvisorySuccess() = runTest {
        val f = NotificationChangesFixture()
        f.local.beforeAdmission = { f.local.setup.session = null }
        assertIs<AppResult.Failed>(f.observe().first())
        assertEquals(0, f.active)
    }

    @Test
    fun parentReplacementCancelsAnAlreadyRegisteredSource() = runTest {
        val f = NotificationChangesFixture()
        val values = Channel<AppResult<Unit>>(8)
        val job = launch { f.observe().collect { values.send(it) } }
        try {
            assertEquals(AppResult.Success(Unit), values.receive())
            f.local.setup.session = null
            f.sessionEvents.value = AppResult.Success(null)
            assertIs<AppResult.Failed>(values.receive())
            assertEquals(0, f.active)
        } finally {
            withContext(NonCancellable) { job.cancelAndJoin() }
            values.close()
        }
    }

    @Test
    fun everyNormallyCompletedUpstreamClosesItsPreviousObservation() = runTest {
        for (part in listOf("source", "admission", "session")) {
            val f = NotificationChangesFixture()
            val finish = CompletableDeferred<Unit>()
            when (part) {
                "source" -> f.source = flow {
                        emit(AppResult.Success(Unit))
                        finish.await()
                    }
                "admission" -> f.admissions = flow {
                        emit(AppResult.Success(f.local.gate.value))
                        finish.await()
                    }
                else -> f.sessions = flow {
                        emit(AppResult.Success(f.local.setup.session))
                        finish.await()
                    }
            }
            val values = Channel<AppResult<Unit>>(8)
            val job = launch { f.observe().collect { values.send(it) } }
            try {
                assertEquals(AppResult.Success(Unit), values.receive(), part)
                finish.complete(Unit)
                assertIs<AppResult.Failed>(values.receive(), part)
                assertEquals(0, f.active, part)
                assertEquals(1, f.started, part)
            } finally {
                withContext(NonCancellable) { job.cancelAndJoin() }
                values.close()
            }
        }
    }

    @Test
    fun sourceExceptionIsAClosedFailureWithoutImplicitRestart() = runTest {
        val f = NotificationChangesFixture()
        f.source = flow { error("Fictional failed registration") }
        assertIs<AppResult.Failed>(f.observe().single())
        assertEquals(1, f.started)
        assertEquals(0, f.active)
    }

    @Test
    fun pendingFinalCheckHasFiniteDeadlineAndCancellationJoinsIt() = runTest {
        val f = NotificationChangesFixture()
        val entered = CompletableDeferred<Unit>()
        var joined = false
        f.local.beforeAdmission = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                joined = true
            }
        }
        assertIs<AppResult.Failed>(f.observe().first())
        assertTrue(entered.isCompleted)
        assertTrue(joined)
        assertEquals(0, f.active)
        joined = false
        val next = CompletableDeferred<Unit>()
        f.local.beforeAdmission = {
            next.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                joined = true
            }
        }
        val values = mutableListOf<AppResult<Unit>>()
        val job = launch { f.observe().collect { values += it } }
        try {
            next.await()
        } finally {
            withContext(NonCancellable) { job.cancelAndJoin() }
        }
        assertTrue(joined)
        assertTrue(values.isEmpty())
        assertEquals(0, f.active)
    }
}
