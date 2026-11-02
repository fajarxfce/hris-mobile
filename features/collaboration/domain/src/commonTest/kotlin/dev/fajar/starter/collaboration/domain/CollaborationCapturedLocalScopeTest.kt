@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationMessages
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationCapturedLocalScopeTest {
    @Test
    fun expiredCredentialsStillAllowOwnedOriginalRecoveryAndProjectionWithoutSelection() = runTest {
        val f = OriginalReceiptFixture()
        f.prepare()
        val scope = f.delivery.capturedLocal()
        assertTrue(
            requireNotNull(f.base.setup.session).binding.expiresAtEpochMillis <
                f.base.setup.now.toEpochMilliseconds()
        )
        assertEquals(AppResult.Success(1), f.recover(scope))
        assertEquals(AppResult.Success(1), f.delivery.reconcile(scope))
        assertEquals(
            "\$original-0",
            assertIs<CollaborationMessageState.Sent>(f.delivery.records.single().state).eventId,
        )
        assertEquals(0, f.delivery.acquisitions)
        assertEquals(0, f.delivery.windows)
        assertNull(f.base.setup.workspace)
    }

    @Test
    fun capturedLocalReceiptRetirementKeepsTheExistingThirtyTwoItemBound() = runTest {
        val f = CollaborationRetirementFixture()
        f.prepare(33)
        val scope = f.delivery.capturedLocal()
        assertEquals(AppResult.Success(32), f.retire(scope))
        assertEquals(1, f.delivery.records.size)
        assertEquals(1, f.delivery.journal.size)
        assertEquals(AppResult.Success(1), f.retire(scope))
        assertEquals(33, f.completions.records.size)
        assertEquals(33, f.original.originals.size)
        assertTrue(f.delivery.records.isEmpty())
        assertTrue(f.delivery.journal.isEmpty())
        assertEquals(0, f.delivery.windows)
    }

    @Test
    fun retainedCompanyDenialStopsCapturedRecoveryWithoutErasingOwnedFacts() = runTest {
        val f = OriginalReceiptFixture()
        f.prepare()
        val scope = f.delivery.capturedLocal()
        f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
        assertEquals(AppResult.Failed(f.base.denied), f.recover(scope))
        assertEquals(AppResult.Failed(f.base.denied), f.delivery.reconcile(scope))
        assertEquals(0, f.reads)
        assertEquals(1, f.originals.size)
        assertEquals(1, f.delivery.records.size)
        assertTrue(f.delivery.journal.isEmpty())
    }

    @Test
    fun aReplacementSessionCannotAppendThePreviousOwnersRecoveredReceipt() = runTest {
        val f = OriginalReceiptFixture()
        f.prepare()
        val scope = f.delivery.capturedLocal()
        f.beforeReadReturns = {
            val session = requireNotNull(f.base.setup.session)
            f.base.setup.session = session.copy(id = "replacement")
        }
        assertIs<AppResult.Failed>(f.recover(scope))
        assertEquals(1, f.reads)
        assertEquals(0, f.delivery.appends)
        assertTrue(f.delivery.journal.isEmpty())
        assertEquals(1, f.originals.size)
    }

    @Test
    fun cancellationWhileReadingOriginalsDoesNotAppendOrRetry() = runTest {
        val f = OriginalReceiptFixture()
        f.prepare()
        val scope = f.delivery.capturedLocal()
        val started = CompletableDeferred<Unit>()
        f.beforeReadReturns = {
            started.complete(Unit)
            awaitCancellation()
        }
        val task = launch { f.recover(scope) }
        started.await()
        task.cancelAndJoin()
        assertEquals(1, f.reads)
        assertEquals(0, f.delivery.appends)
        assertTrue(f.delivery.journal.isEmpty())
        assertEquals(1, f.delivery.records.size)
    }

    @Test
    fun ownerLossAfterAtomicReceiptAppendDoesNotEraseTheCommittedFact() = runTest {
        val f = OriginalReceiptFixture()
        f.prepare()
        val scope = f.delivery.capturedLocal()
        f.delivery.afterAppend = { f.base.setup.session = null }
        assertIs<AppResult.Failed>(f.recover(scope))
        assertEquals(listOf("\$original-0"), f.delivery.journal.single().eventIds)
        assertEquals(1, f.delivery.records.size)
        assertEquals(0, f.delivery.windows)
    }

    @Test
    fun interruptedRetirementKeepsItsArchivedCompletionAndUnremovedReceipt() = runTest {
        val f = CollaborationRetirementFixture()
        f.prepare()
        val scope = f.delivery.capturedLocal()
        f.completions.afterWrite = { f.base.setup.session = null }
        assertIs<AppResult.Failed>(f.retire(scope))
        assertEquals(1, f.completions.records.size)
        assertEquals(1, f.delivery.journal.size)
        assertEquals(0, f.removals)
        assertTrue(f.delivery.records.isEmpty())
        assertEquals(1, f.original.originals.size)
    }

    @Test
    fun capturedAccountAudienceAndSessionFencesAreRequiredEvenForLocalFacts() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        val scope = f.capturedLocal()
        val session = requireNotNull(f.base.setup.session)
        val access = scope.access
        for (invalid in
            listOf(
                access.copy(sessionId = "another"),
                access.copy(accountId = deliveryId(99)),
                access.copy(audience = "https://other.example.test/"),
                access.copy(companyId = "invalid"),
                access.copy(sessionId = ""),
            )) assertIs<AppResult.Failed>(
            collaborationCapturedLocalAccess(invalid, session, f.base.server.audience)
        )
        assertIs<AppResult.Failed>(
            collaborationCapturedLocalAccess(access, null, f.base.server.audience)
        )
        assertIs<AppResult.Failed>(
            collaborationCapturedLocalAccess(
                access,
                session.copy(
                    binding = session.binding.copy(audience = "https://other.example.test/")
                ),
                f.base.server.audience,
            )
        )
        assertEquals(
            AppResult.Success(access),
            collaborationCapturedLocalAccess(access, session, f.base.server.audience),
        )
    }

    @Test
    fun messageObservationUsesCapturedFactsAfterExpiryWithoutReadingAnyWorkspace() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        val scope = f.capturedLocal()
        val identities =
            MutableStateFlow<AppResult<Session?>>(AppResult.Success(f.base.setup.session))
        val observer = f.base.capturedObserver(identities)
        assertEquals(AppResult.Success(f.records), observer(scope, f.conversation).first())
        assertEquals(1, f.base.messageObservations)
        assertEquals(0, f.base.activeMessageObservers)
        assertEquals(0, f.acquisitions)
    }

    @Test
    fun tokenRotationPreservesTheLocalObserverWhileAccountReplacementStopsIt() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        val scope = f.capturedLocal()
        val session = requireNotNull(f.base.setup.session)
        val identities = MutableStateFlow<AppResult<Session?>>(AppResult.Success(session))
        val observer = f.base.capturedObserver(identities)
        val values = mutableListOf<AppResult<List<CollaborationMessageRecord>>>()
        val task = backgroundScope.launch { observer(scope, f.conversation).collect(values::add) }
        runCurrent()
        assertEquals(1, f.base.activeMessageObservers)
        identities.value =
            AppResult.Success(session.copy(tokens = session.tokens.copy(accessToken = "rotated")))
        runCurrent()
        assertEquals(1, f.base.messageObservations)
        identities.value =
            AppResult.Success(session.copy(user = session.user.copy(id = deliveryId(99))))
        runCurrent()
        assertEquals(0, f.base.activeMessageObservers)
        assertIs<AppResult.Failed>(values.last())
        assertEquals(1, f.records.size)
        task.cancelAndJoin()
    }

    @Test
    fun denialClosesTheCapturedMessageObserverUntilExplicitLocalReadmission() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        val scope = f.capturedLocal()
        val identities =
            MutableStateFlow<AppResult<Session?>>(AppResult.Success(f.base.setup.session))
        val observer = f.base.capturedObserver(identities)
        val values = mutableListOf<AppResult<List<CollaborationMessageRecord>>>()
        val task = backgroundScope.launch { observer(scope, f.conversation).collect(values::add) }
        runCurrent()
        f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
        runCurrent()
        assertEquals(AppResult.Failed(f.base.denied), values.last())
        assertEquals(0, f.base.activeMessageObservers)
        f.base.records.value = f.records.map { it.copy(revision = it.revision + 1) }
        runCurrent()
        assertEquals(AppResult.Failed(f.base.denied), values.last())
        f.base.gate.value = CollaborationListAdmission("readmitted", null)
        runCurrent()
        assertEquals(AppResult.Success(f.records), values.last())
        assertEquals(1, f.base.activeMessageObservers)
        task.cancelAndJoin()
        assertEquals(0, f.base.activeMessageObservers)
    }
}

private fun CollaborationDeliveryFixture.capturedLocal(): CollaborationLocalScope.Captured {
    val captured = CollaborationLocalScope.Captured(owner.localListAccess())
    base.setup.workspace = null
    base.setup.beforeWorkspace = { error("Captured local facts cannot read selection") }
    base.setup.now += 365.days
    return captured
}

private fun MessageIntentFixture.capturedObserver(
    identities: StateFlow<AppResult<Session?>>
): ObserveCollaborationMessages {
    val identity =
        object : SessionRepository by setup.sessions {
            override fun observe() = identities

            override suspend fun current() = identities.value
        }
    val noWorkspace =
        object : WorkspaceRepository by setup.workspaces {
            override fun observe(sessionId: String): Nothing = error("No workspace observer")

            override suspend fun current(sessionId: String): Nothing = error("No workspace read")
        }
    return ObserveCollaborationMessages(
        messages,
        lists,
        identity,
        noWorkspace,
        server,
        build,
        clock,
    )
}
