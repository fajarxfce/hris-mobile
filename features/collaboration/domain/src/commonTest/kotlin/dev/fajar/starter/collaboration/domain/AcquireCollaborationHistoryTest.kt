package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.AcquireCollaborationHistory
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest

class AcquireCollaborationHistoryTest {
    @Test
    fun latestAcquisitionUsesTheReturnedHeadAndExplicitAnchorsDoNotReadIt() = runTest {
        val f = HistoryAcquisitionFixture()
        assertEquals(AppResult.Success(CollaborationHistoryAcquisition(20, 0)), f.latest())
        assertEquals(1, f.headReads)
        assertEquals(1, f.reads)
        assertEquals(2, f.local.setup.calls)
        assertIs<AppResult.Success<*>>(f.acquire())
        assertEquals(1, f.headReads)
    }

    @Test
    fun emptyHeadIsReadmittedWithoutClearingExistingFactsOrOpeningAWindow() = runTest {
        val f = HistoryAcquisitionFixture()
        f.retained += f.window.changes
        f.head = f.head.copy(eventId = null)
        assertEquals(AppResult.Success(CollaborationHistoryAcquisition(0, 0)), f.latest())
        assertEquals(f.window.changes, f.retained)
        assertEquals(0, f.reads)
        assertEquals(2, f.local.setup.calls)
        assertTrue(f.batches.isEmpty())
    }

    @Test
    fun foreignAndMalformedHeadsCannotStartDecryption() = runTest {
        val head = HistoryAcquisitionFixture().head
        for (invalid in
            listOf(
                head.copy(owner = head.owner.copy(accountId = "foreign")),
                head.copy(deviceId = "foreign"),
                head.copy(roomId = "!foreign:test"),
                head.copy(eventId = "invalid"),
            )) {
            val f = HistoryAcquisitionFixture()
            f.head = invalid
            assertEquals(
                "client.collaboration_invalid_history_head",
                assertIs<AppResult.Failed>(f.latest()).failure.code,
            )
            assertEquals(0, f.reads)
            assertTrue(f.batches.isEmpty())
        }
    }

    @Test
    fun replacementOrDenialWhileHeadWaitsCannotAcquireContent() = runTest {
        for (denied in listOf(false, true)) {
            val f = HistoryAcquisitionFixture()
            f.beforeHeadReturns = {
                if (denied)
                    f.local.gate.value = CollaborationListAdmission("blocked", f.local.denied)
                else f.local.setup.session = null
            }
            assertIs<AppResult.Failed>(f.latest())
            assertEquals(0, f.reads)
        }
    }

    @Test
    fun cancellationStopsPendingHeadWithoutRetryOrDecryption() = runTest {
        val f = HistoryAcquisitionFixture()
        val entered = CompletableDeferred<Unit>()
        var active = false
        f.beforeHeadReturns = {
            active = true
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                active = false
            }
        }
        val pending = launch { f.latest() }
        entered.await()
        pending.cancelAndJoin()
        assertFalse(active)
        assertEquals(1, f.headReads)
        assertEquals(0, f.reads)
    }

    @Test
    fun sdkTokenRejectionStopsHeadAndWindowWithoutGatingOwnedHistory() = runTest {
        for (head in listOf(false, true)) {
            val f = HistoryAcquisitionFixture()
            val expired =
                Failure(
                    FailureKind.Unauthorized,
                    "SDK token unavailable",
                    code = "client.collaboration_sdk_unauthorized",
                )
            if (head) f.headFailure = expired else f.sourceFailure = expired
            assertEquals(AppResult.Failed(expired), f.latest())
            assertNull(f.local.gate.value.denial)
            assertTrue(f.batches.isEmpty())
        }
    }

    @Test
    fun companyDenialDuringHeadOrEmptyReadmissionRemainsRestricted() = runTest {
        for (duringHead in listOf(false, true)) {
            val f = HistoryAcquisitionFixture()
            if (duringHead) f.headFailure = f.local.denied
            else {
                f.head = f.head.copy(eventId = null)
                f.beforeHeadReturns = { f.delivery.control.remoteFailure = f.local.denied }
            }
            assertEquals(AppResult.Failed(f.local.denied), f.latest())
            assertEquals(f.local.denied, f.local.gate.value.denial)
            assertEquals(0, f.reads)
        }
    }

    @Test
    fun liveWindowIsReadmittedAndStoredInBoundedOriginalBatchesWithoutStartingDelivery() = runTest {
        val f = HistoryAcquisitionFixture()
        assertEquals(AppResult.Success(CollaborationHistoryAcquisition(20, 0)), f.acquire())
        assertEquals(listOf(16, 4), f.batches.map { it.size })
        assertEquals(f.window.changes, f.retained)
        assertEquals(2, f.local.setup.calls)
        assertEquals(listOf("get", "room", "get", "room"), f.admissions)
        assertEquals(1, f.reads)
        assertEquals(0, f.delivery.acquisitions)
        assertEquals(0, f.delivery.windows)
    }

    @Test
    fun expiredOrRestrictedOwnersNeverAcquireNativeHistory() = runTest {
        for (restriction in listOf("expiry", "denial", "storage")) {
            val f = HistoryAcquisitionFixture()
            when (restriction) {
                "expiry" -> f.local.setup.now += 3.hours
                "denial" ->
                    f.local.gate.value = CollaborationListAdmission("blocked", f.local.denied)
                else -> f.local.gateFailure = f.local.storage
            }
            assertIs<AppResult.Failed>(f.acquire())
            assertEquals(0, f.reads)
            assertTrue(f.batches.isEmpty())
        }
    }

    @Test
    fun replacementDuringFinalAdmissionReadCannotStoreTheCapturedWindow() = runTest {
        val f = HistoryAcquisitionFixture()
        f.local.beforeAdmission = { if (f.reads > 0) f.local.setup.session = null }
        assertIs<AppResult.Failed>(f.acquire())
        assertEquals(1, f.reads)
        assertTrue(f.batches.isEmpty())
    }

    @Test
    fun nativeCompanyDenialIsRetainedEvenWhenRestrictPersistenceFails() = runTest {
        val f = HistoryAcquisitionFixture()
        f.sourceFailure = f.local.denied
        assertEquals(AppResult.Failed(f.local.denied), f.acquire())
        assertEquals(f.local.denied, f.local.gate.value.denial)
        f.sourceFailure = f.local.storage
        assertEquals(AppResult.Failed(f.local.denied), f.acquire())
        assertEquals(1, f.reads)
        assertTrue(f.batches.isEmpty())
    }

    @Test
    fun recipientFailureDoesNotCloseCompanyHistory() = runTest {
        val f = HistoryAcquisitionFixture()
        f.sourceFailure =
            Failure(FailureKind.AccessDenied, "Unavailable", code = "collaboration_room_not_found")
        assertEquals(AppResult.Failed(f.sourceFailure!!), f.acquire())
        assertNull(f.local.gate.value.denial)
        assertTrue(f.batches.isEmpty())
    }

    @Test
    fun changedRoomAfterAcquisitionCannotRebindOriginals() = runTest {
        val f = HistoryAcquisitionFixture()
        f.beforeReadReturns = {
            f.delivery.control.room =
                f.delivery.control.room.copy(matrixRoomId = "!changed:example.test")
        }
        assertIs<AppResult.Failed>(f.acquire())
        assertEquals(2, f.local.setup.calls)
        assertTrue(f.batches.isEmpty())
    }

    @Test
    fun cancellationReleasesPendingAcquisitionWithoutRetryOrStorage() = runTest {
        val f = HistoryAcquisitionFixture()
        val entered = CompletableDeferred<Unit>()
        f.beforeReadReturns = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pending = launch { f.acquire() }
        entered.await()
        assertEquals(1, f.active)
        pending.cancelAndJoin()
        assertEquals(0, f.active)
        assertEquals(1, f.reads)
        assertTrue(f.batches.isEmpty())
    }

    @Test
    fun conflictingSecondBatchRetainsFirstFactsAndDoesNotRetryOrClaimCompletion() = runTest {
        val f = HistoryAcquisitionFixture()
        f.rejectBatch = 2
        val failed = assertIs<AppResult.Failed>(f.acquire())
        assertEquals("client.collaboration_history_changed", failed.failure.code)
        assertEquals(16, f.retained.size)
        assertEquals(2, f.batches.size)
        assertEquals(1, f.reads)
    }

    @Test
    fun lostMergeResponseRetainsAlreadyCommittedFactsWithoutRepeatingAcquisition() = runTest {
        val f = HistoryAcquisitionFixture()
        f.mergeFailure = f.local.storage
        assertEquals(AppResult.Failed(f.local.storage), f.acquire())
        assertEquals(16, f.retained.size)
        assertEquals(1, f.batches.size)
        assertEquals(1, f.reads)
    }

    @Test
    fun authorityExpiringDuringCommitStopsTheRemainingBatch() = runTest {
        val f = HistoryAcquisitionFixture()
        f.afterMerge = { f.local.setup.now += 3.hours }
        assertIs<AppResult.Failed>(f.acquire())
        assertEquals(16, f.retained.size)
        assertEquals(1, f.batches.size)
    }

    @Test
    fun returnedCollectionsAreDetachedBeforeAdmissionSuspends() = runTest {
        val f = HistoryAcquisitionFixture()
        val original = f.window.changes.toMutableList()
        val expected = original.toList()
        f.window = f.window.copy(changes = original)
        f.local.beforeAdmission = { if (f.reads > 0) original.clear() }
        assertEquals(AppResult.Success(CollaborationHistoryAcquisition(20, 0)), f.acquire())
        assertEquals(expected, f.retained)
        assertTrue(original.isEmpty())
    }

    @Test
    fun unresolvedAnchorRemainsExplicitWithoutWritingOrAcknowledgingAProviderCursor() = runTest {
        val f = HistoryAcquisitionFixture()
        f.window =
            f.window.copy(
                changes = emptyList(),
                unresolvedEventIds = listOf("\$event-0", "\$state"),
            )
        assertEquals(AppResult.Success(CollaborationHistoryAcquisition(0, 2)), f.acquire())
        assertTrue(f.batches.isEmpty())
        assertEquals(2, f.local.setup.calls)
    }

    @Test
    fun invalidOrForeignWindowsCannotWriteAnyPartialContent() = runTest {
        val first = HistoryAcquisitionFixture().window
        val text = first.changes.first() as CollaborationHistoryChange.Text
        val invalid =
            listOf(
                first.copy(owner = first.owner.copy(accountId = "foreign")),
                first.copy(deviceId = "foreign"),
                first.copy(roomId = "!foreign:test"),
                first.copy(anchorEventId = "\$foreign"),
                first.copy(changes = emptyList()),
                first.copy(changes = listOf(text, text)),
                first.copy(unresolvedEventIds = listOf(text.eventId)),
                first.copy(changes = first.changes + text.copy(eventId = "bad")),
                first.copy(
                    changes = first.changes + text.copy(eventId = "\$bad", contentHash = "bad")
                ),
                first.copy(
                    changes = first.changes + CollaborationHistoryChange.Redacted("\$bad", "bad")
                ),
                first.copy(changes = List(129) { text.copy(eventId = "\$event-$it") }),
            )
        for (window in invalid) {
            val f = HistoryAcquisitionFixture()
            f.window = window
            assertIs<AppResult.Failed>(f.acquire())
            assertTrue(f.batches.isEmpty())
        }
    }

    @Test
    fun invalidSelectionsDoNotReachAdmissionOrTheSdk() = runTest {
        val f = HistoryAcquisitionFixture()
        assertIs<AppResult.Failed>(
            f.useCase("selection", "bad", f.delivery.conversation, "\$event-0")
        )
        assertIs<AppResult.Failed>(
            f.useCase("selection", f.delivery.device.deviceId, "bad", "\$event-0")
        )
        assertIs<AppResult.Failed>(
            f.useCase("selection", f.delivery.device.deviceId, f.delivery.conversation, "bad")
        )
        assertEquals(0, f.local.setup.calls)
        assertEquals(0, f.reads)
    }
}

private class HistoryAcquisitionFixture {
    val delivery = CollaborationDeliveryFixture()
    val local = delivery.base
    val admissions = mutableListOf<String>()
    var window =
        CollaborationOriginalWindow(
            delivery.owner,
            delivery.device.deviceId,
            delivery.device.roomId,
            "\$event-0",
            List(20) {
                CollaborationHistoryChange.Text(
                    "\$event-$it",
                    "@fictional:example.test",
                    null,
                    it.toLong(),
                    "Original $it",
                    "a".repeat(64),
                )
            },
            emptyList(),
        )
    var headReads = 0
    var head =
        CollaborationHistoryHead(
            delivery.owner,
            delivery.device.deviceId,
            delivery.device.roomId,
            "\$event-0",
        )
    var beforeHeadReturns: suspend () -> Unit = {}
    var headFailure: Failure? = null
    var reads = 0
    var active = 0
    var rejectBatch = 0
    var sourceFailure: Failure? = null
    var mergeFailure: Failure? = null
    var beforeReadReturns: suspend () -> Unit = {}
    var afterMerge: suspend () -> Unit = {}
    val batches = mutableListOf<List<CollaborationHistoryChange>>()
    val retained = mutableListOf<CollaborationHistoryChange>()

    init {
        delivery.control.beforeRemote = { admissions += it }
    }

    private val runtimes =
        object : CollaborationRuntimeRepository by delivery.runtimes {
            override suspend fun selfIdentity(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.entities.CollaborationSelfIdentity?
            > = error("No implicit identity acquisition")

            override suspend fun prepareHistoryReopen(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                anchorEventId: String,
            ): AppResult<Boolean> = error("No implicit history reopening")

            override suspend fun readProviderHistory(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                position: String?,
            ): AppResult<CollaborationProviderHistoryPage> =
                error("No implicit provider history page")

            override suspend fun findHistoryHead(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationHistoryHead> {
                assertEquals(delivery.owner, owner)
                assertEquals(delivery.device.deviceId, deviceId)
                assertEquals(delivery.device.roomId, matrixRoomId)
                headReads++
                beforeHeadReturns()
                return headFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(head)
            }

            override suspend fun readOriginalHistory(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                anchorEventId: String,
            ): AppResult<CollaborationOriginalWindow> {
                assertEquals(delivery.owner, owner)
                assertEquals(delivery.device.deviceId, deviceId)
                assertEquals(delivery.device.roomId, matrixRoomId)
                assertEquals("\$event-0", anchorEventId)
                reads++
                active++
                try {
                    val captured = window
                    beforeReadReturns()
                    return sourceFailure?.let { AppResult.Failed(it) }
                        ?: AppResult.Success(captured)
                } finally {
                    active--
                }
            }
        }

    private val history =
        object : CollaborationHistoryRepository {
            override fun observe(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String?,
                after: String?,
                limit: Int,
                before: String?,
                overlap: Int,
            ): Flow<AppResult<CollaborationHistoryPage>> =
                error("Acquisition does not open observers")

            override suspend fun getEventFact(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryEventFact?> = error("No reference reconciliation")

            override suspend fun getByEvent(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No index read")

            override suspend fun getByLogicalId(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                sender: String,
                logicalId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No index read")

            override suspend fun merge(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                changes: List<CollaborationHistoryChange>,
            ): AppResult<Boolean> {
                assertEquals(delivery.owner.localListAccess(), access)
                assertEquals(delivery.conversation, conversationId)
                assertEquals(delivery.device.roomId, roomId)
                assertTrue(changes.size in 1..16)
                batches += changes.toList()
                if (batches.size == rejectBatch) return AppResult.Success(false)
                retained += changes
                afterMerge()
                return mergeFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
            }
        }

    val useCase =
        AcquireCollaborationHistory(
            history,
            delivery.lists,
            delivery.control.remote,
            local.setup.remote,
            runtimes,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
        )

    suspend fun latest() = useCase("selection", delivery.device.deviceId, delivery.conversation)

    suspend fun acquire() =
        useCase("selection", delivery.device.deviceId, delivery.conversation, "\$event-0")
}
