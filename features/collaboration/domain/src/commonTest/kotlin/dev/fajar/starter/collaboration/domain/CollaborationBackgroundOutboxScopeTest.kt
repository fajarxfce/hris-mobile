package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationBackgroundOutboxScopeTest {
    @Test
    fun backgroundStagingRetainsTheOriginalMappingWithoutWorkspaceSelection() = runTest {
        val f = StageFixture()
        f.prepare()
        val elapsed = TestTimeSource()
        val scope = f.base.outboxBackground(f.owner, f.device.deviceId, elapsed)
        elapsed += 40.seconds
        f.base.setup.now += 40.seconds
        assertIs<AppResult.Success<*>>(f.stage(scope))
        assertIs<AppResult.Success<*>>(f.stage(scope))
        assertEquals(
            CollaborationMessageState.Staged(f.device, "original", f.origin),
            f.record.state,
        )
        assertEquals(listOf("save-staging", "enqueue", "save-staged", "close"), f.events)
        assertEquals(1, f.enqueues)
        assertNull(f.base.setup.workspace)
    }

    @Test
    fun neitherStagingNorDeliveryCanUseAnAdmissionForAnotherDevice() = runTest {
        val s = StageFixture()
        s.prepare()
        val scope = s.base.outboxBackground(s.owner, s.device.deviceId, TestTimeSource())
        assertIs<AppResult.Failed>(s.stage(scope, deliveryId(99)))
        assertEquals(0, s.base.setup.calls)
        assertEquals(0, s.acquisitions)
        val d = CollaborationDeliveryFixture()
        d.prepare()
        val admitted = d.base.outboxBackground(d.owner, d.device.deviceId, TestTimeSource())
        assertIs<AppResult.Failed>(d.deliver(admitted, deliveryId(99)))
        assertEquals(0, d.base.setup.calls)
        assertEquals(0, d.acquisitions)
        assertEquals(0, d.windows)
    }

    @Test
    fun expiryDuringPreparationDoesNotEnqueueOrEraseTheQueuedIntent() = runTest {
        val f = StageFixture()
        f.prepare()
        val elapsed = TestTimeSource()
        val scope = f.base.outboxBackground(f.owner, f.device.deviceId, elapsed)
        f.afterPrepare = { elapsed += 60.seconds }
        assertEquals(
            "client.collaboration_revalidation_required",
            assertIs<AppResult.Failed>(f.stage(scope)).failure.code,
        )
        assertEquals(CollaborationMessageState.Queued, f.record.state)
        assertEquals(0, f.enqueues)
        assertEquals(1, f.closes)
        assertNull(f.base.gate.value.denial)
    }

    @Test
    fun expiryAfterNativeCommitRecoversTheSameTransactionWithFreshAdmission() = runTest {
        val f = StageFixture()
        f.prepare()
        val elapsed = TestTimeSource()
        val scope = f.base.outboxBackground(f.owner, f.device.deviceId, elapsed)
        f.afterEnqueue = { elapsed += 60.seconds }
        assertIs<AppResult.Failed>(f.stage(scope))
        assertIs<CollaborationMessageState.Staging>(f.record.state)
        assertEquals(1, f.enqueues)
        f.afterEnqueue = {}
        val renewed = f.base.outboxBackground(f.owner, f.device.deviceId, elapsed)
        assertIs<AppResult.Success<*>>(f.stage(renewed))
        assertEquals(
            "original",
            assertIs<CollaborationMessageState.Staged>(f.record.state).transactionId,
        )
        assertEquals(1, f.enqueues)
        assertEquals(2, f.closes)
    }

    @Test
    fun replacedParentClosesTheLateWriterWithoutStaging() = runTest {
        val f = StageFixture()
        f.prepare()
        val scope = f.base.outboxBackground(f.owner, f.device.deviceId, TestTimeSource())
        f.afterAcquire = {
            val previous = requireNotNull(f.base.setup.session)
            f.base.setup.session =
                previous.copy(binding = previous.binding.copy(serverId = deliveryId(99)))
        }
        assertIs<AppResult.Failed>(f.stage(scope))
        assertEquals(1, f.closes)
        assertEquals(0, f.preparations)
        assertEquals(0, f.enqueues)
        assertEquals(CollaborationMessageState.Queued, f.record.state)
    }

    @Test
    fun sdkCredentialFailureDoesNotRestrictLocalHistoryForEitherScope() = runTest {
        for (background in listOf(false, true)) {
            val f = StageFixture()
            f.prepare()
            val scope =
                if (background)
                    f.base.outboxBackground(f.owner, f.device.deviceId, TestTimeSource())
                else CollaborationOperationScope.Selected("selection")
            val failed =
                Failure(
                    FailureKind.Unauthorized,
                    "Reconnect chat.",
                    code = "client.collaboration_sdk_unauthorized",
                )
            f.prepareFailure = failed
            assertEquals(AppResult.Failed(failed), f.stage(scope))
            assertNull(f.base.gate.value.denial)
            assertEquals(CollaborationMessageState.Queued, f.record.state)
            assertEquals(1, f.closes)
            assertEquals(0, f.enqueues)
        }
    }

    @Test
    fun cancellationCannotDiscardAReceivedCompanyDenialWhenGateCleanupFails() = runTest {
        val f = StageFixture()
        f.prepare()
        val scope = f.base.outboxBackground(f.owner, f.device.deviceId, TestTimeSource())
        f.prepareFailure = f.base.denied
        f.afterPrepare = { currentCoroutineContext().cancel() }
        val task = launch { f.stage(scope) }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(f.base.denied, f.base.gate.value.denial)
        assertEquals(0, f.enqueues)
        assertEquals(1, f.closes)
    }

    @Test
    fun backgroundDeliveryUsesTheExistingReceiptJournalAndProjection() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        val scope = f.base.outboxBackground(f.owner, f.device.deviceId, TestTimeSource())
        assertIs<AppResult.Success<*>>(f.deliver(scope))
        assertEquals(listOf("resume", "journal", "project", "close"), f.events)
        assertEquals(
            "\$first",
            assertIs<CollaborationMessageState.Sent>(f.records.single().state).eventId,
        )
        assertEquals(1, f.windows)
        assertNull(f.base.setup.workspace)
    }

    @Test
    fun expiryAfterSendingRetainsReceiptAndFreshAdmissionUsesOnlyTheOriginalMapping() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        val elapsed = TestTimeSource()
        val scope = f.base.outboxBackground(f.owner, f.device.deviceId, elapsed)
        f.afterResume = { elapsed += 60.seconds }
        assertIs<AppResult.Failed>(f.deliver(scope))
        assertEquals(listOf("\$first"), f.journal.single().eventIds)
        assertIs<CollaborationMessageState.Staged>(f.records.single().state)
        assertEquals(0, f.projections)
        val original = f.records.single().intent
        // Delivery rechecks the SDK queue with known Sent mappings as well, retaining aliases.
        // Its fixture prohibits enqueue; a drained queue yields no new message or receipt.
        f.window =
            CollaborationQueueDelivery(
                emptyList(),
                emptySet(),
                CollaborationQueueDeliveryReason.DRAINED,
            )
        f.afterResume = {
            assertEquals(
                "\$first",
                assertIs<CollaborationMessageState.Sent>(f.records.single().state).eventId,
            )
            assertEquals(setOf("original"), f.knownTransactions)
        }
        val renewed = f.base.outboxBackground(f.owner, f.device.deviceId, elapsed)
        assertIs<AppResult.Success<*>>(f.deliver(renewed))
        assertEquals(original, f.records.single().intent)
        assertEquals(listOf("\$first"), f.journal.single().eventIds)
        assertEquals(2, f.windows)
        assertEquals(2, f.closes)
    }

    @Test
    fun parentReplacementAfterSendingRetainsAccountFencedReceiptWithoutPublication() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        val scope = f.base.outboxBackground(f.owner, f.device.deviceId, TestTimeSource())
        f.afterResume = {
            val previous = requireNotNull(f.base.setup.session)
            f.base.setup.session =
                previous.copy(binding = previous.binding.copy(serverId = deliveryId(99)))
        }
        assertIs<AppResult.Failed>(f.deliver(scope))
        assertEquals(listOf("\$first"), f.journal.single().eventIds)
        assertIs<CollaborationMessageState.Staged>(f.records.single().state)
        assertEquals(0, f.projections)
        assertEquals(1, f.closes)
        assertEquals(1, f.windows)
    }

    @Test
    fun cancellationAfterReturnedSendResultRetainsReceiptBeforeClosingWriter() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        val scope = f.base.outboxBackground(f.owner, f.device.deviceId, TestTimeSource())
        f.afterResume = { currentCoroutineContext().cancel() }
        val task = launch { f.deliver(scope) }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(listOf("\$first"), f.journal.single().eventIds)
        assertEquals(listOf("resume", "journal", "close"), f.events)
        assertEquals(0, f.projections)
        assertEquals(1, f.windows)
    }

    @Test
    fun deniedCompanyAfterSendRetainsReceiptAndCannotPublishOrRetry() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        val scope = f.base.outboxBackground(f.owner, f.device.deviceId, TestTimeSource())
        f.afterResume = { f.base.gate.value = CollaborationListAdmission("denied", f.base.denied) }
        assertEquals(AppResult.Failed(f.base.denied), f.deliver(scope))
        assertEquals(AppResult.Failed(f.base.denied), f.deliver(scope))
        assertEquals(listOf("\$first"), f.journal.single().eventIds)
        assertEquals(0, f.projections)
        assertEquals(1, f.windows)
        assertEquals(1, f.acquisitions)
    }
}

private fun MessageIntentFixture.outboxBackground(
    owner: CollaborationOwner,
    deviceId: String,
    elapsed: TestTimeSource,
): CollaborationOperationScope.Background {
    setup.workspace = null
    setup.beforeWorkspace = { error("Background outbox cannot read foreground selection") }
    val now = setup.now
    return CollaborationOperationScope.Background(
        CollaborationBackgroundAdmission(
            CollaborationAdmissionObservation(owner, deviceId, 1, 1, 1, now, now + 60.seconds),
            now,
            now + 60.seconds,
            elapsed.markNow() + 60.seconds,
        )
    )
}
