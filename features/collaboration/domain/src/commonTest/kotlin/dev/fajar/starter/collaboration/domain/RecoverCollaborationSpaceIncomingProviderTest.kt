package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.usecases.RecoverCollaborationSpaceIncomingProvider
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class RecoverCollaborationSpaceIncomingProviderTest {
    @Test
    fun originalTargetCannotReadOrRequeuePublishedHistory() = runTest {
        val f = Fixture()
        val target =
            CollaborationSpaceIncomingTarget.Original(
                CollaborationOriginalConversationPosition(
                    "10000000-0000-4000-8000-000000007100",
                    assertIs<CollaborationSpaceIncomingTarget.Published>(f.captured.target)
                        .conversationId,
                )
            )
        val captured = f.captured.copy(target = target, sourceId = "1".repeat(64))
        val before = f.base.request.snapshot
        val result = f.useCase(f.base.access, f.base.key, captured)
        assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(result).failure.kind)
        assertEquals(before, f.base.request.snapshot)
        assertEquals(0, f.base.request.writes)
        assertTrue(f.base.request.base.calls.isEmpty())
    }

    @Test
    fun producerBeforeCasRetriesOnceAndAfterCommitConfirmsLostReply() = runTest {
        for (stage in listOf("write", "committed")) {
            val f = Fixture()
            val request = f.base.request
            val wake = request.wake
            request.responseLoss = stage == "committed"
            request.base.hooks[stage] = { request.retainIndependentProducerWork() }
            val result = f.revisit()
            if (stage == "write") {
                val failure = assertIs<AppResult.Failed>(result).failure
                assertTrue(isCollaborationSpaceIncomingQueueContention(failure))
                assertEquals(1L, failure.retryAfterSeconds)
            } else assertEquals(AppResult.Success(true), result)
            val retained = request.snapshot!!.demand
            assertEquals("later", retained.sources.single().position)
            assertNotNull(retained.sources.single().inventoryGeneration)
            assertEquals("!producer:test", retained.physicalRooms.single().roomId)
            assertEquals(wake, request.wake)
            assertEquals(1, request.writes)
            assertEquals(0, request.schedules)
        }
    }

    @Test
    fun absentKnownHeadRechecksLatestAfterDurableDelayWithoutDiscardingFacts() = runTest {
        val f = Fixture()
        val provider = f.base.latest
        val source = f.base.request.source
        val nextPosition = f.base.request.snapshot!!.demand.nextPosition
        assertEquals(AppResult.Success(true), f.revisit())
        val room = f.base.request.snapshot!!.demand.rooms.single()
        assertEquals(
            f.captured.copy(
                position = nextPosition,
                phase = CollaborationSpaceIncomingPhase.Latest,
                retry =
                    nextCollaborationSpaceIncomingRetry(
                        null,
                        f.base.request.base.native.clock.now().toEpochMilliseconds(),
                    ),
            ),
            room,
        )
        assertEquals(provider, f.base.latest)
        assertEquals(source, f.base.request.source)
        assertEquals(f.base.originalWake, f.base.request.wake)
        assertEquals(0, f.base.request.schedules)
    }

    @Test
    fun acquiredHeadUnfinishedProviderAndPendingContentCannotRestartLatest() = runTest {
        for (mode in 0..3) {
            val f = Fixture()
            when (mode) {
                0 -> f.base.request.covered()
                1 -> f.base.request.covered(exhausted = false)
                2 -> f.base.request.covered(unresolved = true)
                else ->
                    f.base.request.pending = CollaborationHistoryReferences(null, emptyList(), null)
            }
            assertEquals(AppResult.Success(false), f.revisit())
            assertEquals(0, f.base.request.writes)
        }
    }

    @Test
    fun startupWithoutKnownHeadDoesNotInventAProviderRetry() = runTest {
        val f = Fixture()
        val captured = f.captured.copy(headEventId = null)
        f.replace(captured)
        assertEquals(AppResult.Success(false), f.useCase(f.base.access, f.base.key, captured))
        assertEquals(0, f.base.request.writes)
    }

    @Test
    fun lostCommitReplyAndReplayKeepOneRecheckAndOneDelay() = runTest {
        val f = Fixture()
        f.base.request.responseLoss = true
        assertEquals(AppResult.Success(true), f.revisit())
        val saved = f.base.request.snapshot
        repeat(3) { assertEquals(AppResult.Success(true), f.revisit()) }
        assertEquals(saved, f.base.request.snapshot)
        assertEquals(1, f.base.request.writes)
        assertEquals(f.base.originalWake, f.base.request.wake)
    }

    @Test
    fun replacedOrAbsentRoomDoesNotReviveAnOldGeneration() = runTest {
        for (absent in listOf(false, true)) {
            val f = Fixture()
            if (absent) f.base.request.snapshot = null
            else f.replace(f.captured.copy(generation = "c1000000-0000-4000-8000-000000009999"))
            val before = f.base.request.snapshot
            assertEquals(AppResult.Success(false), f.revisit())
            assertEquals(before, f.base.request.snapshot)
            assertEquals(0, f.base.request.writes)
        }
    }

    @Test
    fun newerProviderProgressCannotBeUsedAsProofOfAnUnseenHead() = runTest {
        val f = Fixture()
        f.base.latest = f.base.latest!!.copy(revision = f.base.latest!!.revision + 1)
        assertEquals(AppResult.Success(false), f.revisit())
        assertEquals(0, f.base.request.writes)
    }

    @Test
    fun concurrentNewRequestSurvivesTheOldRecheckCommit() = runTest {
        val f = Fixture()
        val newer = f.captured.copy(generation = "c1000000-0000-4000-8000-000000009999")
        f.base.request.base.hooks["write"] = {
            f.replace(newer)
            f.base.request.wake = CollaborationSpaceIncomingWake(newer.generation)
        }
        assertEquals(AppResult.Success(false), f.revisit())
        assertEquals(newer, f.base.request.snapshot!!.demand.rooms.single())
        assertEquals(newer.generation, f.base.request.wake!!.generation)
    }

    @Test
    fun accountReplacementAtEachBoundaryPreventsAStaleSuccess() = runTest {
        for (stage in
            listOf(
                "demand",
                "binding",
                "pending",
                "source",
                "progress",
                "write",
                "committed",
                "readback",
            )) {
            val f = Fixture()
            f.base.request.base.hooks[stage] = { f.base.request.base.native.setup.session = null }
            assertIs<AppResult.Failed>(f.revisit(), stage)
            assertEquals(f.base.originalWake, f.base.request.wake)
        }
    }

    @Test
    fun cancellationAfterTheTransactionRetainsTheDelayedRecheck() = runTest {
        val f = Fixture()
        f.base.request.base.hooks["committed"] = { awaitCancellation() }
        assertNull(withTimeoutOrNull(1_000) { f.revisit() })
        val saved = f.base.request.snapshot!!.demand.rooms.single()
        assertEquals(CollaborationSpaceIncomingPhase.Latest, saved.phase)
        assertNotNull(saved.retry)
        assertEquals(f.base.originalWake, f.base.request.wake)
        assertEquals(1, f.base.request.writes)
    }

    @Test
    fun blockedLocalReadTimesOutWithoutASecondAttempt() = runTest {
        val f = Fixture()
        f.base.request.base.hooks["source"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.revisit()).failure.kind)
        assertEquals(0, f.base.request.writes)
        assertEquals(1, f.base.request.base.calls.count { it == "source" })
    }

    @Test
    fun pureTransitionRejectsOtherPhasesUnknownHeadsAndInvalidRetry() {
        val f = Fixture()
        val now = f.base.request.base.native.clock.now().toEpochMilliseconds()
        val retry = nextCollaborationSpaceIncomingRetry(null, now)!!
        for (room in
            listOf(
                f.captured.copy(headEventId = null),
                f.captured.copy(phase = CollaborationSpaceIncomingPhase.Latest),
                f.captured.copy(phase = CollaborationSpaceIncomingPhase.Provider),
            )) {
            assertIs<AppResult.Failed>(
                nextCollaborationSpaceIncomingDemand(
                    f.base.request.snapshot!!.demand,
                    CollaborationSpaceIncomingDemandChange.RestartProvider(room, retry),
                )
            )
        }
        assertIs<AppResult.Failed>(
            nextCollaborationSpaceIncomingDemand(
                f.base.request.snapshot!!.demand,
                CollaborationSpaceIncomingDemandChange.RestartProvider(
                    f.captured,
                    retry.copy(failures = 0),
                ),
            )
        )
    }

    @Test
    fun earlierFailureIncreasesTheRetainedRecheckDelay() = runTest {
        val f = Fixture()
        val now = f.base.request.base.native.clock.now().toEpochMilliseconds()
        val captured =
            f.captured.copy(retry = CollaborationSpaceIncomingRetry(now - 60_000, now, 2))
        f.replace(captured)
        assertEquals(AppResult.Success(true), f.useCase(f.base.access, f.base.key, captured))
        assertEquals(
            CollaborationSpaceIncomingRetry(now, now + 120_000, 3),
            f.base.request.snapshot!!.demand.rooms.single().retry,
        )
    }

    @Test
    fun absentCurrentProgressStartsLatestWithoutTouchingEarlierSourceFacts() = runTest {
        val f = Fixture()
        val earlierSource = f.base.request.source
        f.missingProgress()
        assertEquals(AppResult.Success(true), f.revisit())
        val current = f.base.request.snapshot!!.demand.rooms.single()
        assertEquals(CollaborationSpaceIncomingPhase.Latest, current.phase)
        assertNull(current.retry)
        assertEquals(earlierSource, f.base.request.source)
        assertFalse("source" in f.base.request.base.calls)
        assertEquals(f.base.originalWake, f.base.request.wake)
        assertEquals(1, f.base.request.writes)
    }

    @Test
    fun unknownHeadAndEarlierRetryDoNotBlockAnEmptyCurrentTraversal() = runTest {
        val f = Fixture()
        val now = f.base.request.base.native.clock.now().toEpochMilliseconds()
        val captured =
            f.captured.copy(
                headEventId = null,
                retry = CollaborationSpaceIncomingRetry(now - 30_000, now, 1),
            )
        f.replace(captured)
        f.missingProgress()
        assertEquals(AppResult.Success(true), f.useCase(f.base.access, f.base.key, captured))
        val current = f.base.request.snapshot!!.demand.rooms.single()
        assertEquals(CollaborationSpaceIncomingPhase.Latest, current.phase)
        assertNull(current.retry)
        assertNull(current.headEventId)
        assertEquals(f.base.originalWake, f.base.request.wake)
    }

    @Test
    fun providerProgressAppearingDuringRecoveryPreventsAnObsoleteRestart() = runTest {
        val f = Fixture()
        val appeared = f.base.latest
        f.missingProgress()
        f.base.latest = appeared
        assertEquals(AppResult.Success(false), f.revisit())
        assertEquals(0, f.base.request.writes)
        assertFalse("source" in f.base.request.base.calls)
    }

    @Test
    fun responseLossAndReplayKeepAnImmediateProviderRestartIdempotent() = runTest {
        val f = Fixture()
        f.missingProgress()
        f.base.request.responseLoss = true
        assertEquals(AppResult.Success(true), f.revisit())
        val retained = f.base.request.snapshot
        repeat(3) { assertEquals(AppResult.Success(true), f.revisit()) }
        assertEquals(retained, f.base.request.snapshot)
        assertNull(retained!!.demand.rooms.single().retry)
        assertEquals(1, f.base.request.writes)
    }

    @Test
    fun aStorageFailureCannotBeInterpretedAsMissingProgress() = runTest {
        for (stage in listOf("pending", "progress")) {
            val f = Fixture()
            f.missingProgress()
            val retained = f.base.request.snapshot
            f.base.request.base.failures[stage] = f.base.request.base.directory.storageFailure
            assertIs<AppResult.Failed>(f.revisit())
            assertEquals(retained, f.base.request.snapshot)
            assertEquals(0, f.base.request.writes)
        }
    }

    private class Fixture {
        val base = SpaceIncomingCompletionFixture()
        val captured = base.captured
        val useCase =
            RecoverCollaborationSpaceIncomingProvider(
                base.demands,
                base.request.base.bindings,
                base.history,
                base.request.base.directory.directory,
                base.request.base.native.setup.sessions,
                base.request.base.native.server,
                base.request.base.native.clock,
            )

        init {
            base.request.source = base.request.source.copy(reference = null)
        }

        fun replace(room: CollaborationSpaceIncomingRoomDemand) {
            base.request.snapshot =
                base.request.snapshot!!.copy(
                    demand = base.request.snapshot!!.demand.copy(rooms = listOf(room))
                )
        }

        fun missingProgress() {
            base.request.pending = CollaborationHistoryReferences(null, emptyList(), null)
            base.latest = null
        }

        suspend fun revisit() = useCase(base.access, base.key, captured)
    }
}
