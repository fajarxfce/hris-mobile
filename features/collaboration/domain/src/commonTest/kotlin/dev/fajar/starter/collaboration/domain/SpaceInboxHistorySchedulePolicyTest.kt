package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceInboxHistorySchedulePolicyTest {
    @Test
    fun onlyExactConditionalWriteContentionDefersTheBackgroundProjection() {
        val busy =
            Failure(
                FailureKind.Conflict,
                "Conditional write changed",
                code = "client.collaboration_inbox_busy",
            )
        assertEquals(
            CollaborationSpaceInboxHistoryTurnStatus.Contended,
            collaborationSpaceInboxHistoryFailureStatus(busy),
        )
        for (failure in
            listOf(
                busy.copy(kind = FailureKind.Storage),
                busy.copy(kind = FailureKind.AccessDenied),
                busy.copy(code = "another_conflict"),
                busy.copy(code = null),
            )) assertEquals(
            CollaborationSpaceInboxHistoryTurnStatus.Failed,
            collaborationSpaceInboxHistoryFailureStatus(failure),
        )
    }

    @Test
    fun firstObservedAbsenceIsARealOnceOnlyOwnerRecovery() {
        val f = HistoryScheduleFixture()
        val first = f.run(CollaborationSpaceInboxHistorySchedule())
        assertTrue(first.recoverParked)
        assertEquals(f.frame, first.frame)
        assertFalse(first.consumed.ownerRecoveryPending)
        assertFalse(first.consumed.metadataRecoveryPending)
        assertEquals(f.frame.trigger, first.consumed.lastTrigger)
    }

    @Test
    fun idleAndFailureDoNotRepeatAnUnchangedAttempt() {
        val f = HistoryScheduleFixture()
        for (status in
            listOf(
                CollaborationSpaceInboxHistoryTurnStatus.Idle,
                CollaborationSpaceInboxHistoryTurnStatus.Failed,
                CollaborationSpaceInboxHistoryTurnStatus.Contended,
            )) {
            val consumed = f.run(CollaborationSpaceInboxHistorySchedule()).consumed
            val stopped = completeCollaborationSpaceInboxHistorySchedule(consumed, status)
            repeat(3) {
                assertEquals(
                    AppResult.Success(CollaborationSpaceInboxHistoryScheduleDecision.Wait),
                    scheduleCollaborationSpaceInboxHistory(stopped, f.frame),
                )
            }
        }
    }

    @Test
    fun advancedWorkContinuesWithoutReopeningParkedWorkOnEveryStep() {
        val f = HistoryScheduleFixture()
        val first = f.run(CollaborationSpaceInboxHistorySchedule())
        val continuing =
            completeCollaborationSpaceInboxHistorySchedule(
                first.consumed,
                CollaborationSpaceInboxHistoryTurnStatus.Advanced,
            )
        val next = f.run(continuing)
        assertFalse(next.recoverParked)
        assertFalse(next.consumed.continuing)
        val ended =
            completeCollaborationSpaceInboxHistorySchedule(
                next.consumed,
                CollaborationSpaceInboxHistoryTurnStatus.Idle,
            )
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxHistoryScheduleDecision.Wait),
            scheduleCollaborationSpaceInboxHistory(ended, f.frame),
        )
    }

    @Test
    fun ordinaryDestinationRevisionsCannotRestartFailedHistory() {
        val f = HistoryScheduleFixture()
        val version = CollaborationSpaceInboxVersion(f.id, 1, 1)
        val first =
            f.frame.copy(
                trigger = f.frame.trigger.copy(destinationGeneration = f.id),
                destination = version,
            )
        val consumed = f.run(CollaborationSpaceInboxHistorySchedule(), first).consumed
        val stopped =
            completeCollaborationSpaceInboxHistorySchedule(
                consumed,
                CollaborationSpaceInboxHistoryTurnStatus.Failed,
            )
        val later = first.copy(destination = version.copy(revision = 90, lastOrdinal = 90))
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxHistoryScheduleDecision.Wait),
            scheduleCollaborationSpaceInboxHistory(stopped, later),
        )
    }

    @Test
    fun actualHistoryCatalogAndCompletedRepairDemandEachAllowAnotherAttempt() {
        val f = HistoryScheduleFixture()
        val consumed = f.run(CollaborationSpaceInboxHistorySchedule()).consumed
        val stopped =
            completeCollaborationSpaceInboxHistorySchedule(
                consumed,
                CollaborationSpaceInboxHistoryTurnStatus.Failed,
            )
        for (trigger in
            listOf(
                f.frame.trigger.copy(historySignal = f.id),
                f.frame.trigger.copy(completedRepairDemand = f.id),
                f.frame.trigger.copy(
                    catalog = CollaborationSpaceConversationCatalogVersion(f.id, 0)
                ),
            )) {
            val next = f.run(stopped, f.frame.copy(trigger = trigger))
            assertFalse(next.recoverParked)
            assertEquals(trigger, next.consumed.lastTrigger)
        }
    }

    @Test
    fun destinationReplacementIsDifferentFromOrdinaryContentAdvancement() {
        val f = HistoryScheduleFixture()
        val consumed = f.run(CollaborationSpaceInboxHistorySchedule()).consumed
        val changed =
            f.frame.copy(
                trigger = f.frame.trigger.copy(destinationGeneration = f.id),
                destination = CollaborationSpaceInboxVersion(f.id, 0, 1),
            )
        val next = f.run(consumed, changed)
        assertEquals(changed, next.frame)
        assertFalse(next.recoverParked)
    }

    @Test
    fun metadataFailureStopsContinuationAndRecoveryIsConsumedOnce() {
        val f = HistoryScheduleFixture()
        val first = f.run(CollaborationSpaceInboxHistorySchedule()).consumed
        val continuing =
            completeCollaborationSpaceInboxHistorySchedule(
                first,
                CollaborationSpaceInboxHistoryTurnStatus.Advanced,
            )
        val blocked = suspendCollaborationSpaceInboxHistorySchedule(continuing)
        assertFalse(blocked.continuing)
        assertEquals(blocked, suspendCollaborationSpaceInboxHistorySchedule(blocked))
        val resumed = f.run(blocked)
        assertTrue(resumed.recoverParked)
        val failed =
            completeCollaborationSpaceInboxHistorySchedule(
                resumed.consumed,
                CollaborationSpaceInboxHistoryTurnStatus.Failed,
            )
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxHistoryScheduleDecision.Wait),
            scheduleCollaborationSpaceInboxHistory(failed, f.frame),
        )
    }

    @Test
    fun malformedOrMismatchedDestinationNeverCreatesAnInvocation() {
        val f = HistoryScheduleFixture()
        for (frame in
            listOf(
                f.frame.copy(trigger = f.frame.trigger.copy(historySignal = "invalid")),
                f.frame.copy(destination = CollaborationSpaceInboxVersion(f.id, 1, 1)),
                f.frame.copy(trigger = f.frame.trigger.copy(destinationGeneration = f.id)),
            )) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(
                    scheduleCollaborationSpaceInboxHistory(
                        CollaborationSpaceInboxHistorySchedule(),
                        frame,
                    )
                )
                .failure
                .kind,
        )
    }
}

private class HistoryScheduleFixture {
    val id = "e8000000-0000-4000-8000-000000000001"
    val frame =
        CollaborationSpaceInboxHistoryFrame(
            CollaborationSpaceInboxHistoryTrigger(null, null, null, null),
            null,
        )

    fun run(
        current: CollaborationSpaceInboxHistorySchedule,
        value: CollaborationSpaceInboxHistoryFrame = frame,
    ) =
        assertIs<CollaborationSpaceInboxHistoryScheduleDecision.Run>(
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryScheduleDecision>>(
                    scheduleCollaborationSpaceInboxHistory(current, value)
                )
                .value
        )
}
