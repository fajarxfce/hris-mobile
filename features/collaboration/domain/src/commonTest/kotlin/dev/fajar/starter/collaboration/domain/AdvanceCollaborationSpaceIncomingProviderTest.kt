package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.AdvanceCollaborationSpaceIncomingProvider
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class AdvanceCollaborationSpaceIncomingProviderTest {
    @Test
    fun firstRetainedPageMovesLatestToProviderWithoutAcknowledgingTheWake() = runTest {
        val f = Fixture(exhausted = false)
        assertEquals(AppResult.Success(true), f.advance())
        assertEquals(CollaborationSpaceIncomingPhase.Provider, f.room().phase)
        assertEquals(f.base.originalWake, f.request.wake)
        assertEquals(1, f.request.writes)
        assertEquals(0, f.request.schedules)
    }

    @Test
    fun exhaustedLatestMovesToPendingButDoesNotCompleteContent() = runTest {
        val f = Fixture()
        assertEquals(AppResult.Success(true), f.advance())
        assertEquals(CollaborationSpaceIncomingPhase.Pending, f.room().phase)
        assertEquals(f.captured.generation, f.room().generation)
        assertEquals(f.base.originalWake, f.request.wake)
        assertFalse("source" in f.request.base.calls || "pending" in f.request.base.calls)
    }

    @Test
    fun continuedProviderWaveMovesToPendingOnlyAfterItsRetainedReceiptMatches() = runTest {
        val f = Fixture(phase = CollaborationSpaceIncomingPhase.Provider)
        assertEquals(AppResult.Success(true), f.advance())
        assertEquals(CollaborationSpaceIncomingPhase.Pending, f.room().phase)
        assertEquals(1, f.request.base.calls.count { it == "progress" })
    }

    @Test
    fun unfinishedContinuationPreservesProviderWithoutAQueueWrite() = runTest {
        val f = Fixture(exhausted = false, phase = CollaborationSpaceIncomingPhase.Provider)
        assertEquals(AppResult.Success(true), f.advance())
        assertEquals(f.captured, f.room())
        assertEquals(0, f.request.writes)
        assertEquals(listOf("demand", "progress"), f.request.base.calls)
    }

    @Test
    fun cachedSavedExhaustionIsValidEvidenceForAPreviouslyClaimedProviderPhase() = runTest {
        val f = Fixture(phase = CollaborationSpaceIncomingPhase.Provider)
        val saved = f.receipt.previous!!.copy(next = null)
        f.receipt = f.receipt.copy(previous = saved, progress = saved, references = 0)
        f.base.latest = saved
        assertEquals(AppResult.Success(true), f.advance())
        assertEquals(CollaborationSpaceIncomingPhase.Pending, f.room().phase)
    }

    @Test
    fun savedReceiptCannotAcknowledgeLatestAndLatestCannotReplaceAContinuationCapture() = runTest {
        for (phase in
            listOf(
                CollaborationSpaceIncomingPhase.Latest,
                CollaborationSpaceIncomingPhase.Provider,
            )) {
            val f = Fixture(phase = phase)
            f.receipt =
                f.receipt.copy(
                    origin =
                        if (phase == CollaborationSpaceIncomingPhase.Latest)
                            CollaborationHistoryPageOrigin.SavedPosition
                        else CollaborationHistoryPageOrigin.Latest
                )
            assertIs<AppResult.Failed>(f.advance())
            assertTrue(f.request.base.calls.isEmpty())
        }
    }

    @Test
    fun mismatchedOwnerRuntimeAndLogicalDeviceCannotReachStorage() = runTest {
        for (mode in 0..3) {
            val f = Fixture()
            f.receipt =
                when (mode) {
                    0 ->
                        f.receipt.copy(
                            runtime =
                                f.receipt.runtime.copy(
                                    owner =
                                        f.receipt.runtime.owner.copy(
                                            spaceId = f.request.provider.queue.id(901)
                                        )
                                )
                        )
                    1 ->
                        f.receipt.copy(
                            runtime =
                                f.receipt.runtime.copy(deviceId = f.request.provider.queue.id(902))
                        )
                    2 -> f.receipt.copy(runtime = f.receipt.runtime.copy(operationId = "invalid"))
                    else ->
                        f.receipt.copy(
                            traversal =
                                f.receipt.traversal.copy(
                                    conversationId = f.request.provider.queue.id(903)
                                )
                        )
                }
            assertIs<AppResult.Failed>(f.advance())
            assertTrue(f.request.base.calls.isEmpty())
        }
    }

    @Test
    fun invalidFirstPageRevisionCountsOrPendingSeekCannotServeAsAReceipt() = runTest {
        for (mode in 0..6) {
            val f = Fixture()
            val p = f.receipt.progress
            f.receipt =
                when (mode) {
                    0 -> f.receipt.copy(references = 21)
                    1 -> f.receipt.copy(progress = p.copy(revision = 1))
                    2 -> f.receipt.copy(progress = p.copy(references = 19))
                    3 -> f.receipt.copy(progress = p.copy(provider = null))
                    4 -> f.receipt.copy(progress = p.copy(incoming = null))
                    5 ->
                        f.receipt.copy(
                            progress = p.copy(incoming = p.incoming!!.copy(until = "invented"))
                        )
                    else ->
                        f.receipt.copy(
                            progress =
                                p.copy(
                                    incoming =
                                        p.incoming!!.copy(
                                            pendingAfter = "cursor",
                                            pendingThrough = 10,
                                        )
                                )
                        )
                }
            assertIs<AppResult.Failed>(f.advance())
            assertTrue(f.request.base.calls.isEmpty())
        }
        for (mode in 0..5) {
            val f = Fixture(phase = CollaborationSpaceIncomingPhase.Provider)
            val p = f.receipt.progress
            f.receipt =
                f.receipt.copy(
                    progress =
                        when (mode) {
                            0 -> p.copy(generation = f.request.provider.queue.id(906))
                            1 ->
                                p.copy(
                                    provider =
                                        p.provider!!.copy(
                                            deviceId = f.request.provider.queue.id(907)
                                        )
                                )
                            2 -> p.copy(revision = f.before!!.revision)
                            3 -> p.copy(next = f.before!!.next)
                            4 -> p.copy(references = f.before!!.references - 1)
                            else -> p.copy(references = f.before!!.references + 21)
                        }
                )
            assertIs<AppResult.Failed>(f.advance())
            assertTrue(f.request.base.calls.isEmpty())
        }
    }

    @Test
    fun receiptMustStillMatchTheLocalProviderCheckpoint() = runTest {
        val f = Fixture()
        f.base.latest = f.receipt.progress.copy(revision = f.receipt.progress.revision + 1)
        assertIs<AppResult.Failed>(f.advance())
        assertEquals(0, f.request.writes)
        assertEquals(f.captured, f.room())
    }

    @Test
    fun absentOrNewerRoomGenerationCannotBeAdvancedByAnOldReceipt() = runTest {
        for (absent in listOf(false, true)) {
            val f = Fixture()
            if (absent) f.request.snapshot = null
            else f.setRoom(f.captured.copy(generation = f.request.provider.queue.id(904)))
            assertEquals(AppResult.Success(false), f.advance())
            assertEquals(listOf("demand"), f.request.base.calls)
            assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun changedFactsUnderTheSameGenerationCannotBeReinterpretedAsTheCapturedWork() = runTest {
        val f = Fixture()
        f.setRoom(f.captured.copy(headEventId = "\$changed"))
        assertIs<AppResult.Failed>(f.advance())
        assertEquals(listOf("demand"), f.request.base.calls)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun concurrentNewRequestAndWakeSurviveTheOldPhaseCompareAndSet() = runTest {
        val f = Fixture()
        val generation = f.request.provider.queue.id(905)
        f.request.base.hooks["write"] = {
            f.setRoom(f.captured.copy(generation = generation, headEventId = "\$newer"))
            f.request.wake = CollaborationSpaceIncomingWake(generation)
        }
        assertIs<AppResult.Failed>(f.advance())
        assertEquals(generation, f.room().generation)
        assertEquals(CollaborationSpaceIncomingWake(generation), f.request.wake)
        assertEquals(1, f.request.writes)
    }

    @Test
    fun lostPhaseWriteReplyIsConfirmedExactlyAndNeverReissued() = runTest {
        val f = Fixture()
        f.request.responseLoss = true
        assertEquals(AppResult.Success(true), f.advance())
        assertEquals(CollaborationSpaceIncomingPhase.Pending, f.room().phase)
        assertEquals(1, f.request.writes)
        assertEquals(f.base.originalWake, f.request.wake)
    }

    @Test
    fun absentOrUnreadablePhaseReadbackDoesNotClaimSuccess() = runTest {
        for (lost in listOf(false, true)) {
            val f = Fixture()
            if (lost)
                f.request.base.failures["readback"] =
                    Failure(FailureKind.Storage, "Fictional missing readback")
            else f.request.commit = false
            assertIs<AppResult.Failed>(f.advance())
            assertEquals(1, f.request.writes)
            assertEquals(f.base.originalWake, f.request.wake)
        }
    }

    @Test
    fun cancellationAfterPhaseCommitDoesNotEraseTheNewPhaseOrWake() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        f.request.base.hooks["committed"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.advance() }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(CollaborationSpaceIncomingPhase.Pending, f.room().phase)
        assertEquals(f.base.originalWake, f.request.wake)
        assertEquals(1, f.request.writes)
    }

    @Test
    fun oldTransportCredentialsDoNotHideOwnedRetainedPhaseEvidence() = runTest {
        val f = Fixture()
        f.request.base.native.setup.now += 365.days
        assertEquals(AppResult.Success(true), f.advance())
    }

    @Test
    fun currentDenialStopsBeforeAnyQueueOrProviderRead() = runTest {
        val f = Fixture()
        f.request.base.directory.deny()
        assertEquals(
            f.request.base.directory.denied,
            assertIs<AppResult.Failed>(f.advance()).failure,
        )
        assertTrue(f.request.base.calls.isEmpty())
    }

    @Test
    fun accountReplacementAfterEveryReadOrWriteCannotContinueUnderTheOldOwner() = runTest {
        for (stage in listOf("demand", "progress", "write", "committed", "readback")) {
            val f = Fixture()
            f.request.base.hooks[stage] = { f.request.base.native.setup.session = null }
            assertIs<AppResult.Failed>(f.advance())
            assertEquals(f.base.originalWake, f.request.wake)
            if (stage in listOf("demand", "progress")) assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun stalledCheckpointReadHasAFiniteBudgetAndNeverRetries() = runTest {
        val f = Fixture()
        f.request.base.hooks["progress"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.advance()).failure.kind)
        assertEquals(1, f.request.base.calls.count { it == "progress" })
        assertEquals(0, f.request.writes)
    }

    @Test
    fun browseReceiptCannotAdvanceIncomingEvenWithOtherwiseValidCoordinates() = runTest {
        val f = Fixture()
        f.receipt =
            f.receipt.copy(
                traversal = f.receipt.traversal.copy(purpose = CollaborationHistoryPurpose.Browse),
                progress = f.receipt.progress.copy(incoming = null),
            )
        assertIs<AppResult.Failed>(f.advance())
        assertTrue(f.request.base.calls.isEmpty())
    }

    @Test
    fun alreadyAppliedExactPhaseIsIdempotentWithoutRecheckingAnOlderProviderRevision() = runTest {
        val f = Fixture()
        f.setRoom(f.captured.copy(phase = CollaborationSpaceIncomingPhase.Pending))
        f.base.latest = f.receipt.progress.copy(revision = f.receipt.progress.revision + 1)
        assertEquals(AppResult.Success(true), f.advance())
        assertEquals(listOf("demand"), f.request.base.calls)
        assertEquals(0, f.request.writes)
        assertEquals(f.base.originalWake, f.request.wake)
    }

    private class Fixture(
        exhausted: Boolean = true,
        phase: CollaborationSpaceIncomingPhase = CollaborationSpaceIncomingPhase.Latest,
    ) {
        val base = SpaceIncomingCompletionFixture()
        val request = base.request
        val captured = base.captured.copy(phase = phase)
        val before =
            if (phase == CollaborationSpaceIncomingPhase.Provider) request.provider.checkpoint()
            else null
        val after =
            request.provider
                .checkpoint(if (exhausted) null else if (before == null) "next" else "older")
                .copy(
                    revision = before?.revision?.plus(1) ?: 0,
                    references = (before?.references ?: 0) + 20,
                )
        var receipt =
            CollaborationSpaceProviderHistoryAcquisition(
                request.provider.runtime,
                base.key,
                if (phase == CollaborationSpaceIncomingPhase.Latest)
                    CollaborationHistoryPageOrigin.Latest
                else CollaborationHistoryPageOrigin.SavedPosition,
                before,
                after,
                20,
            )
        val useCase =
            AdvanceCollaborationSpaceIncomingProvider(
                request.demands,
                base.history,
                request.base.directory.directory,
                request.base.native.setup.sessions,
                request.base.native.server,
            )

        init {
            setRoom(captured)
            base.latest = after
        }

        fun setRoom(value: CollaborationSpaceIncomingRoomDemand) {
            request.snapshot =
                request.snapshot!!.copy(
                    demand = request.snapshot!!.demand.copy(rooms = listOf(value))
                )
        }

        fun room() = request.snapshot!!.demand.rooms.single()

        suspend fun advance() = useCase(base.access, captured, receipt)
    }
}
