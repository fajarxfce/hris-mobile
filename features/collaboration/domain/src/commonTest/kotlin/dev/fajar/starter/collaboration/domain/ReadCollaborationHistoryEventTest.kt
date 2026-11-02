package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadCollaborationHistoryEventTest {
    @Test
    fun capturedOriginalAndTombstoneRemainReadableWithoutFreshCredentialsOrWorkspace() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        val scope = f.scope()
        f.local.setup.workspace = null
        f.local.setup.beforeWorkspace = { error("No selected workspace") }
        f.local.setup.now += 365.days
        for ((id, fact) in f.facts) assertEquals(
            AppResult.Success(fact),
            f.eventReader(scope, f.key, id),
        )
        assertEquals(AppResult.Success(null), f.eventReader(scope, f.key, "\$missing"))
        assertEquals(0, f.reads)
        assertEquals(0, f.writes)
        assertEquals(3, f.factReads)
        assertTrue(f.accessed.all { it == scope.access })
    }

    @Test
    fun invalidOrForeignRequestsCannotOpenFactStorage() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        val scope = f.scope()
        assertIs<AppResult.Failed>(f.eventReader(scope, f.key, "invalid"))
        assertIs<AppResult.Failed>(
            f.eventReader(scope, f.key.copy(conversationId = "invalid"), "\$event-0")
        )
        assertIs<AppResult.Failed>(
            f.eventReader(
                scope.copy(
                    access = scope.access.copy(accountId = "a5000000-0000-4000-8000-000000000001")
                ),
                f.key,
                "\$event-0",
            )
        )
        assertEquals(0, f.factReads)
    }

    @Test
    fun malformedOrSubstitutedFactCannotAcknowledgeAHint() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        val scope = f.scope()
        val id = "\$event-0"
        val fact = f.facts.getValue(id)
        for (invalid in
            listOf(
                fact.copy(eventId = "\$other"),
                fact.copy(sender = "invalid"),
                fact.copy(timestamp = -1),
                fact.copy(timestamp = null, redacted = false),
            )) {
            f.facts[id] = invalid
            assertIs<AppResult.Failed>(f.eventReader(scope, f.key, id))
        }
    }

    @Test
    fun confirmedDenialGatesInitialAndLateResultsEvenIfStorageOrSessionAlsoFails() = runTest {
        for (late in listOf(false, true)) {
            val f = HistoryReferenceReconciliationFixture()
            val scope = f.scope()
            val deny: suspend () -> Unit = {
                f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                f.local.setup.sessionFailure = f.local.storage
            }
            if (late) {
                f.factFailure = f.local.storage
                f.afterFact = deny
            } else f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
            assertEquals(AppResult.Failed(f.local.denied), f.eventReader(scope, f.key, "\$event-0"))
            assertEquals(if (late) 1 else 0, f.factReads)
        }
    }

    @Test
    fun lateAccountReplacementAndCancellationCannotPublishAndPendingIoIsJoined() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        val scope = f.scope()
        val session = f.local.setup.session
        f.afterFact = { f.local.setup.session = session!!.copy(id = "replacement") }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.eventReader(scope, f.key, "\$event-0")).failure.kind,
        )
        f.local.setup.session = session
        val entered = CompletableDeferred<Unit>()
        var closed = false
        f.afterFact = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                closed = true
            }
        }
        val job = launch {
            f.eventReader(scope, f.key, "\$event-0")
            error("Cancelled result")
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(closed)
        f.afterFact = {}
        assertIs<AppResult.Success<*>>(f.eventReader(scope, f.key, "\$event-0"))
    }

    private fun HistoryReferenceReconciliationFixture.scope(): CollaborationLocalScope.Captured {
        val session = local.setup.session!!
        return CollaborationLocalScope.Captured(
            CollaborationLocalAccess(
                session.id,
                local.server.audience,
                session.user.id,
                local.summary!!.conversation.companyId,
            )
        )
    }
}
