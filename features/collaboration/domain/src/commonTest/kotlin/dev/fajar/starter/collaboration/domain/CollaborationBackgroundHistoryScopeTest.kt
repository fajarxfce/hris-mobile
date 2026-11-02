package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationBackgroundHistoryScopeTest {
    @Test
    fun originalsReuseBoundedMergeAtFortySecondsWithoutReadingSelection() = runTest {
        val f = HistoryAcquisitionFixture()
        val elapsed = TestTimeSource()
        val scope = f.delivery.historyBackground(elapsed)
        f.local.setup.now += 40.seconds
        elapsed += 40.seconds
        assertEquals(
            AppResult.Success(CollaborationHistoryAcquisition(20, 0)),
            f.useCase(scope, f.delivery.device.deviceId, f.delivery.conversation, "\$event-0"),
        )
        assertEquals(listOf(16, 4), f.batches.map { it.size })
        assertEquals(f.window.changes, f.retained)
        assertNull(f.local.setup.workspace)
        assertEquals(0, f.delivery.acquisitions)
        assertEquals(0, f.delivery.windows)
    }

    @Test
    fun providerPageUsesTheSameProgressCommitWithoutChangingSelection() = runTest {
        val f = ProviderHistoryAcquisitionFixture()
        val scope = f.delivery.historyBackground(TestTimeSource())
        assertEquals(
            AppResult.Success(CollaborationProviderHistoryAcquisition(20, false)),
            f.useCase(scope, f.traversal.deviceId, f.traversal.conversationId),
        )
        assertEquals(listOf(f.page), f.saved)
        assertEquals("next", f.progress?.next)
        assertNull(f.local.setup.workspace)
    }

    @Test
    fun anotherDeviceCannotReuseEitherHistoryAdmission() = runTest {
        val originals = HistoryAcquisitionFixture()
        val originalScope = originals.delivery.historyBackground(TestTimeSource())
        val pages = ProviderHistoryAcquisitionFixture()
        val pageScope = pages.delivery.historyBackground(TestTimeSource())
        assertIs<AppResult.Failed>(
            originals.useCase(originalScope, deliveryId(99), originals.delivery.conversation)
        )
        assertIs<AppResult.Failed>(
            pages.useCase(pageScope, deliveryId(99), pages.traversal.conversationId)
        )
        assertEquals(0, originals.local.setup.calls)
        assertEquals(0, originals.reads)
        assertEquals(0, pages.local.setup.calls)
        assertEquals(0, pages.reads)
    }

    @Test
    fun monotonicExpiryDuringOriginalAcquisitionCannotMergeReturnedContent() = runTest {
        val f = HistoryAcquisitionFixture()
        val elapsed = TestTimeSource()
        val scope = f.delivery.historyBackground(elapsed)
        f.beforeReadReturns = { elapsed += 60.seconds }
        val result =
            f.useCase(scope, f.delivery.device.deviceId, f.delivery.conversation, "\$event-0")
        assertEquals(
            "client.collaboration_revalidation_required",
            assertIs<AppResult.Failed>(result).failure.code,
        )
        assertTrue(f.batches.isEmpty())
        assertEquals(0, f.active)
        assertNull(f.local.gate.value.denial)
    }

    @Test
    fun aReplacedParentCannotAdvanceProviderHistory() = runTest {
        val f = ProviderHistoryAcquisitionFixture()
        val scope = f.delivery.historyBackground(TestTimeSource())
        f.beforePageReturns = {
            val session = requireNotNull(f.local.setup.session)
            f.local.setup.session =
                session.copy(binding = session.binding.copy(serverId = deliveryId(99)))
        }
        assertIs<AppResult.Failed>(
            f.useCase(scope, f.traversal.deviceId, f.traversal.conversationId)
        )
        assertEquals(1, f.reads)
        assertEquals(0, f.stages)
        assertNull(f.progress)
        assertEquals(0, f.active)
    }

    @Test
    fun concurrentCompanyDenialPreservesPreviouslyOwnedOriginals() = runTest {
        val f = HistoryAcquisitionFixture()
        val scope = f.delivery.historyBackground(TestTimeSource())
        f.retained += f.window.changes.first()
        f.beforeReadReturns = {
            f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
        }
        assertEquals(
            AppResult.Failed(f.local.denied),
            f.useCase(scope, f.delivery.device.deviceId, f.delivery.conversation, "\$event-0"),
        )
        assertEquals(listOf(f.window.changes.first()), f.retained)
        assertTrue(f.batches.isEmpty())
    }

    @Test
    fun cancelledDeliveryOfCompanyDenialStillClosesTheGateWhenCleanupFails() = runTest {
        val f = HistoryAcquisitionFixture()
        val scope = f.delivery.historyBackground(TestTimeSource())
        f.sourceFailure = f.local.denied
        f.beforeReadReturns = { currentCoroutineContext().cancel() }
        val task = launch {
            f.useCase(scope, f.delivery.device.deviceId, f.delivery.conversation, "\$event-0")
        }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(f.local.denied, f.local.gate.value.denial)
        assertTrue(f.batches.isEmpty())
        assertEquals(0, f.active)
    }

    @Test
    fun cancellationReleasesThePendingHistoryReaderWithoutRetrying() = runTest {
        val f = HistoryAcquisitionFixture()
        val scope = f.delivery.historyBackground(TestTimeSource())
        val entered = CompletableDeferred<Unit>()
        f.beforeReadReturns = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val task = launch {
            f.useCase(scope, f.delivery.device.deviceId, f.delivery.conversation, "\$event-0")
        }
        entered.await()
        task.cancelAndJoin()
        assertEquals(0, f.active)
        assertEquals(1, f.reads)
        assertTrue(f.batches.isEmpty())
    }

    @Test
    fun ownerLossAfterAtomicPageCommitDoesNotEraseItsRetainedProgress() = runTest {
        val f = ProviderHistoryAcquisitionFixture()
        val scope = f.delivery.historyBackground(TestTimeSource())
        f.afterStage = { f.local.setup.session = null }
        assertIs<AppResult.Failed>(
            f.useCase(scope, f.traversal.deviceId, f.traversal.conversationId)
        )
        assertEquals(listOf(f.page), f.saved)
        assertEquals("next", f.progress?.next)
        assertEquals(1, f.stages)
    }
}

private fun CollaborationDeliveryFixture.historyBackground(
    elapsed: TestTimeSource
): CollaborationOperationScope.Background {
    base.setup.workspace = null
    base.setup.beforeWorkspace = { error("Background history cannot read selection") }
    val now = base.setup.now
    return CollaborationOperationScope.Background(
        CollaborationBackgroundAdmission(
            CollaborationAdmissionObservation(
                owner,
                device.deviceId,
                1,
                1,
                1,
                now,
                now + 60.seconds,
            ),
            now,
            now + 60.seconds,
            elapsed.markNow() + 60.seconds,
        )
    )
}
