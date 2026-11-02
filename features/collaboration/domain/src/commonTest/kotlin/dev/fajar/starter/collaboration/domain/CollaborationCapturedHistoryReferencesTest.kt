package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationCapturedHistoryReferencesTest {
    @Test
    fun expiredCapturedOwnershipReadsAndReconcilesWithoutWorkspaceOrProviderCursorReset() =
        runTest {
            val f = HistoryReferenceReconciliationFixture()
            val scope = f.scope()
            f.local.setup.workspace = null
            f.local.setup.beforeWorkspace = { error("No selected company dependency") }
            f.local.setup.now += 365.days
            val before = f.progress
            val read =
                assertIs<AppResult.Success<CollaborationHistoryReferences>>(
                        f.reader(scope, f.key, limit = 2)
                    )
                    .value
            assertEquals(f.references.take(2), read.events)
            assertEquals(before, read.progress)
            val reconciled =
                assertIs<AppResult.Success<CollaborationHistoryReconciliation>>(
                        f.useCase(scope, f.key, limit = 2)
                    )
                    .value
            assertEquals(2, reconciled.resolved)
            assertEquals(0, reconciled.unresolved)
            assertEquals(before.next, f.progress.next)
            assertEquals(before.generation, f.progress.generation)
            assertEquals(0, f.local.setup.calls)
            assertEquals(0, f.activeReads)
            assertTrue(f.accessed.all { it == scope.access })
        }

    @Test
    fun aDifferentForegroundCompanyNeverRedirectsCapturedReferenceStorage() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        val scope = f.scope()
        val selected = f.local.setup.workspace!!
        f.local.setup.workspace =
            selected.copy(
                id = "different-selection",
                company = selected.company.copy(id = differentAccount),
            )
        f.local.setup.beforeWorkspace = {
            error("Captured reference operations do not read workspace")
        }
        assertIs<AppResult.Success<*>>(f.reader(scope, f.key))
        assertIs<AppResult.Success<*>>(f.useCase(scope, f.key))
        assertTrue(f.accessed.isNotEmpty())
        assertTrue(f.accessed.all { it == scope.access })
        assertEquals("different-selection", f.local.setup.workspace!!.id)
    }

    @Test
    fun foreignAccountOrAudienceCannotAcquireReferencesOrResolveFacts() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        val captured = f.scope().access
        for (access in
            listOf(
                captured.copy(accountId = differentAccount),
                captured.copy(audience = "https://foreign.example.test/"),
            )) {
            val scope = CollaborationLocalScope.Captured(access)
            assertIs<AppResult.Failed>(f.reader(scope, f.key))
            assertIs<AppResult.Failed>(f.useCase(scope, f.key))
        }
        assertTrue(f.accessed.isEmpty())
        assertEquals(0, f.writes)
    }

    @Test
    fun lateDenialRetainsPriorityOverConcurrentStorageAndSessionFailures() = runTest {
        for (reconcile in listOf(false, true)) {
            val f = HistoryReferenceReconciliationFixture()
            val scope = f.scope()
            f.readFailure = f.local.storage
            f.afterRead = {
                f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                f.local.setup.sessionFailure = f.local.storage
            }
            val result = if (reconcile) f.useCase(scope, f.key) else f.reader(scope, f.key)
            assertEquals(AppResult.Failed(f.local.denied), result)
            assertEquals(0, f.writes)
            assertEquals(0, f.activeReads)
        }
    }

    @Test
    fun accountReplacementDuringFactLookupPreventsAcknowledgment() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        val scope = f.scope()
        f.afterFact = { f.local.setup.session = f.local.setup.session!!.copy(id = "replacement") }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.useCase(scope, f.key)).failure.kind,
        )
        assertEquals(0, f.writes)
        assertTrue(f.references.none { it.resolved })
    }

    @Test
    fun lateAccountReplacementSuppressesSuccessButKeepsCommittedReferenceFacts() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        val scope = f.scope()
        val session = f.local.setup.session
        f.afterWrite = { f.local.setup.session = session!!.copy(id = "replacement") }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.useCase(scope, f.key)).failure.kind,
        )
        assertEquals(2, f.references.count { it.resolved })
        assertEquals(1, f.writes)
        f.local.setup.session = session
        val pending =
            assertIs<AppResult.Success<CollaborationHistoryReferences>>(f.reader(scope, f.key))
                .value
        assertEquals(f.references.takeLast(1), pending.events)
        assertEquals("provider-next", pending.progress!!.next)
    }

    @Test
    fun cancellingPendingCapturedOperationsClosesTheirSubscriptionsAndAllowsLaterRecovery() =
        runTest {
            for (reconcile in listOf(false, true)) {
                val f = HistoryReferenceReconciliationFixture()
                val scope = f.scope()
                val entered = CompletableDeferred<Unit>()
                f.afterRead = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                val job = launch {
                    if (reconcile) f.useCase(scope, f.key) else f.reader(scope, f.key)
                }
                entered.await()
                job.cancelAndJoin()
                assertEquals(0, f.activeReads)
                assertEquals(0, f.writes)
                f.afterRead = {}
                assertIs<AppResult.Success<*>>(f.reader(scope, f.key))
            }
        }

    @Test
    fun alreadyCancelledCapturedOperationsDoNotOpenAdmissionOrReferenceStorage() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        val scope = f.scope()
        f.local.beforeAdmission = { error("Cancelled operation acquired admission storage") }
        for (reconcile in listOf(false, true)) {
            val job = launch {
                currentCoroutineContext().cancel()
                if (reconcile) f.useCase(scope, f.key) else f.reader(scope, f.key)
            }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertTrue(f.accessed.isEmpty())
        assertEquals(0, f.writes)
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

    private companion object {
        const val differentAccount = "a5000000-0000-4000-8000-000000000001"
    }
}
