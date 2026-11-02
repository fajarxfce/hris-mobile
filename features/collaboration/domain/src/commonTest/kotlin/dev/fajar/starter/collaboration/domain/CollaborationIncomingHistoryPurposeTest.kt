package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.TestTimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationIncomingHistoryPurposeTest {
    @Test
    fun incomingAcquisitionUsesItsExplicitPartitionAndFreshBackgroundAdmission() = runTest {
        val f = ProviderHistoryAcquisitionFixture(CollaborationHistoryPurpose.Incoming)
        val scope = f.delivery.historyBackground(TestTimeSource())
        assertIs<AppResult.Success<*>>(
            f.useCase(
                scope,
                f.traversal.deviceId,
                f.traversal.conversationId,
                purpose = CollaborationHistoryPurpose.Incoming,
            )
        )
        f.page = f.page.copy(requestedPosition = "next", start = "next", next = null)
        assertEquals(
            AppResult.Success(CollaborationProviderHistoryAcquisition(20, true)),
            f.useCase(
                scope,
                f.traversal.deviceId,
                f.traversal.conversationId,
                purpose = CollaborationHistoryPurpose.Incoming,
            ),
        )
        assertEquals(listOf(null, "next"), f.positions)
        assertEquals(2, f.stages)
        assertNull(f.local.setup.workspace)
        assertEquals(0, f.active)
    }

    @Test
    fun selectedIncomingAcquisitionAlsoPreservesTheExplicitPurpose() = runTest {
        val f = ProviderHistoryAcquisitionFixture(CollaborationHistoryPurpose.Incoming)
        assertIs<AppResult.Success<*>>(f.acquire())
        val previous = f.progress
        assertIs<AppResult.Success<*>>(f.acquire(CollaborationHistoryPageOrigin.Latest))
        assertEquals(previous, f.stagedProgress.last())
        assertEquals(listOf<String?>(null, null), f.positions)
    }

    @Test
    fun denialAndCancellationCannotAdvanceAnIncomingPartition() = runTest {
        for (cancel in listOf(false, true)) {
            val f = ProviderHistoryAcquisitionFixture(CollaborationHistoryPurpose.Incoming)
            val scope = f.delivery.historyBackground(TestTimeSource())
            val entered = CompletableDeferred<Unit>()
            f.beforePageReturns = {
                if (cancel) {
                    entered.complete(Unit)
                    awaitCancellation()
                } else f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
            }
            val operation = suspend {
                f.useCase(
                    scope,
                    f.traversal.deviceId,
                    f.traversal.conversationId,
                    purpose = CollaborationHistoryPurpose.Incoming,
                )
            }
            if (cancel) {
                val task = launch { operation() }
                entered.await()
                task.cancelAndJoin()
            } else assertEquals(AppResult.Failed(f.local.denied), operation())
            assertEquals(0, f.stages)
            assertEquals(0, f.active)
            assertNull(f.progress)
        }
    }

    @Test
    fun capturedPendingReferencesReconcileInTheSameIncomingPartitionOffline() = runTest {
        val f = HistoryReferenceReconciliationFixture(CollaborationHistoryPurpose.Incoming)
        val access =
            CollaborationLocalAccess(
                f.local.setup.session!!.id,
                f.local.server.audience,
                f.local.setup.session!!.user.id,
                f.local.summary!!.conversation.companyId,
            )
        val scope = CollaborationLocalScope.Captured(access)
        f.local.setup.workspace = null
        f.local.setup.beforeWorkspace = { error("No foreground selection") }
        val original = f.progress
        assertEquals(
            3,
            assertIs<AppResult.Success<CollaborationHistoryReferences>>(f.reader(scope, f.key))
                .value
                .events
                .size,
        )
        assertEquals(
            2,
            assertIs<AppResult.Success<CollaborationHistoryReconciliation>>(f.useCase(scope, f.key))
                .value
                .resolved,
        )
        assertEquals(original.next, f.progress.next)
        assertEquals(
            1,
            assertIs<AppResult.Success<CollaborationHistoryReferences>>(f.reader(scope, f.key))
                .value
                .events
                .size,
        )
        assertEquals(0, f.local.setup.calls)
        assertEquals(0, f.activeReads)
    }
}
