package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceInventoryRetryPolicyTest {
    @Test
    fun firstFailureKeepsItsCursorGenerationPositionAndIndependentWork() {
        val current =
            demand.copy(
                rescanGeneration = id(8),
                nextPosition = 1,
                physicalRooms =
                    listOf(
                        CollaborationSpaceIncomingPhysicalRoomDemand(
                            source.storeId,
                            "!room:test",
                            id(9),
                            0,
                            "\$head",
                            CollaborationSpaceIncomingMembership.Joined,
                            false,
                        )
                    ),
            )
        val next = defer(current, captured, 1_000)
        assertEquals(current.copy(sources = listOf(captured.copy(inventoryRetry = retry))), next)
    }

    @Test
    fun continuousResponsePositionsSurviveDeferralAndConfirmation() {
        val current = demand.copy(sources = listOf(captured.copy(position = "later")))
        val next = defer(current, captured, 1_000)
        assertEquals("later", next.sources.single().position)
        val readback =
            next.copy(sources = listOf(next.sources.single().copy(position = "later-still")))
        assertEquals(
            AppResult.Success(true),
            confirmCollaborationSpaceInventoryDeferral(
                captured,
                next.sources.single(),
                readback,
                false,
            ),
        )
    }

    @Test
    fun newerInventoryGenerationAndCursorCannotBeDelayedByTheOldAttempt() {
        for (newer in
            listOf(
                captured.copy(inventoryGeneration = id(2)),
                captured.copy(inventoryAfterRoomId = "!z:test"),
            )) {
            val current = demand.copy(sources = listOf(newer))
            assertEquals(current, defer(current, captured, 1_000))
        }
    }

    @Test
    fun lostReplyReplayCannotExtendTheSameDeferral() {
        val retained = defer(demand, captured, 1_000)
        assertEquals(retained, defer(retained, captured, 2_000))
        assertEquals(retained, defer(retained, captured, 100_000))
    }

    @Test
    fun repeatedWakesBeforeEligibilityDoNotExtendTheDeadline() {
        val retained = defer(demand, captured, 1_000)
        val source = retained.sources.single()
        assertFalse(eligibleCollaborationSpaceInventory(source, 30_999))
        assertTrue(eligibleCollaborationSpaceInventory(source, 31_000))
        assertEquals(retained, defer(retained, source, 30_999))
    }

    @Test
    fun retryDelayGrowsOnlyForNewEligibleAttemptsAndStaysCapped() {
        var current = demand
        var now = 1_000L
        for (delay in listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L)) {
            current = defer(current, current.sources.single(), now)
            val retry = assertNotNull(current.sources.single().inventoryRetry)
            assertEquals(delay, retry.eligibleAtEpochMillis - retry.attemptedAtEpochMillis)
            now = retry.eligibleAtEpochMillis
        }
    }

    @Test
    fun aBackwardClockCanResumeInsteadOfWaitingForAnOldWallClock() {
        val current = defer(demand, captured, 1_000)
        assertTrue(eligibleCollaborationSpaceInventory(current.sources.single(), 500))
        val next = defer(current, current.sources.single(), 500)
        assertEquals(
            CollaborationSpaceIncomingRetry(500, 60_500, 2),
            next.sources.single().inventoryRetry,
        )
    }

    @Test
    fun anotherOriginalsProgressIsPreservedAndIdentityReuseIsRejected() {
        val other =
            captured.copy(
                source = source.copy(storeId = "b".repeat(64)),
                inventoryGeneration = id(2),
            )
        val next = defer(demand.copy(sources = listOf(captured, other)), captured, 1_000)
        assertEquals(other, next.sources[1])
        val malformed =
            demand.copy(
                sources = listOf(captured.copy(source = source.copy(matrixDeviceId = "OTHER")))
            )
        assertIs<AppResult.Failed>(deferCollaborationSpaceInventory(malformed, captured, 1_000))
    }

    @Test
    fun knownCasMissDistinguishesAnUnchangedRetryFromObsoleteWork() {
        val deferred = defer(demand, captured, 1_000).sources.single()
        assertTrue(
            isCollaborationSpaceIncomingQueueContention(
                assertIs<AppResult.Failed>(
                        confirmCollaborationSpaceInventoryDeferral(captured, deferred, demand, true)
                    )
                    .failure
            )
        )
        val newer = demand.copy(sources = listOf(captured.copy(inventoryGeneration = id(2))))
        assertEquals(
            AppResult.Success(false),
            confirmCollaborationSpaceInventoryDeferral(captured, deferred, newer, true),
        )
        assertIs<AppResult.Failed>(
            confirmCollaborationSpaceInventoryDeferral(captured, deferred, newer, false)
        )
    }

    @Test
    fun anArbitraryValidRetryCannotPretendToBeTheExpectedNextAttempt() {
        val forged =
            captured.copy(inventoryRetry = CollaborationSpaceIncomingRetry(1_000, 301_000, 5))
        assertIs<AppResult.Failed>(
            confirmCollaborationSpaceInventoryDeferral(
                captured,
                forged,
                demand.copy(sources = listOf(forged)),
                false,
            )
        )
    }

    @Test
    fun invalidClockAndCompletedInventoryAreNotEligible() {
        assertFalse(eligibleCollaborationSpaceInventory(captured, -1))
        assertFalse(
            eligibleCollaborationSpaceInventory(captured.copy(inventoryGeneration = null), 1_000)
        )
        assertIs<AppResult.Failed>(deferCollaborationSpaceInventory(demand, captured, -1))
    }

    private val source =
        CollaborationSpaceIncomingSource("a".repeat(64), "@original:test", "ORIGINAL")
    private val captured =
        CollaborationSpaceIncomingSourceProgress(source, "before", id(1), "!m:test")
    private val demand = CollaborationSpaceIncomingDemand(sources = listOf(captured))
    private val retry = CollaborationSpaceIncomingRetry(1_000, 31_000, 1)

    private fun defer(
        current: CollaborationSpaceIncomingDemand,
        captured: CollaborationSpaceIncomingSourceProgress,
        now: Long,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                deferCollaborationSpaceInventory(current, captured, now)
            )
            .value

    private fun id(value: Int) = "10000000-0000-4000-8000-" + value.toString().padStart(12, '0')
}
