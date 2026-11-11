package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class CollaborationHistoryRecoveryPolicyTest {
    private val active = "a1000000-0000-4000-8000-000000009800"
    private val requested = "a1000000-0000-4000-8000-000000009801"
    private val position = CollaborationHistoryRecoveryPosition(active, 17)
    private val progress =
        CollaborationHistoryProgress(
            active,
            3,
            "next",
            20,
            CollaborationIncomingHistoryPosition("head", null),
        )

    @Test
    fun existingBrowseAndIncomingProgressRemainReadableWithoutRecovery() {
        assertTrue(validCollaborationHistoryProgress(progress))
        assertTrue(validCollaborationHistoryProgress(progress.copy(incoming = null)))
    }

    @Test
    fun unfinishedBoundaryDoesNotGrowWithLaterAppends() {
        val value = progress.copy(recovery = position.copy(after = "opaque-cursor"))
        assertTrue(validCollaborationHistoryProgress(value))
        assertTrue(validCollaborationHistoryProgress(value.copy(references = 50)))
        assertEquals(17, value.recovery!!.through)
    }

    @Test
    fun completedAttemptMayStillContainUnresolvedContent() {
        assertTrue(
            validCollaborationHistoryRecoveryPosition(
                position.copy(complete = true, unresolved = true),
                20,
            )
        )
    }

    @Test
    fun emptyBoundaryIsOnlyACompletedCleanAttempt() {
        assertTrue(
            validCollaborationHistoryRecoveryPosition(
                position.copy(through = 0, complete = true),
                0,
            )
        )
        assertFalse(validCollaborationHistoryRecoveryPosition(position.copy(through = 0), 0))
        assertFalse(
            validCollaborationHistoryRecoveryPosition(
                position.copy(through = 0, complete = true, unresolved = true),
                0,
            )
        )
    }

    @Test
    fun boundaryAndReferenceCountsMustRemainExactlyRepresentableAndOwned() {
        for (through in listOf(-1L, 21L, Long.MAX_VALUE)) {
            assertFalse(
                validCollaborationHistoryRecoveryPosition(position.copy(through = through), 20)
            )
        }
        for (references in listOf(-1L, 9_007_199_254_740_992L, Long.MAX_VALUE)) {
            assertFalse(validCollaborationHistoryRecoveryPosition(position, references))
        }
    }

    @Test
    fun malformedActiveAndRequestedPassesAreRejected() {
        for (value in
            listOf(
                "",
                "00000000-0000-0000-0000-000000000000",
                "not-a-generation",
                "A1000000-0000-4000-8000-000000009800",
            )) {
            assertFalse(
                validCollaborationHistoryRecoveryPosition(position.copy(generation = value), 20)
            )
            assertFalse(
                validCollaborationHistoryRecoveryPosition(
                    position.copy(requestedGeneration = value),
                    20,
                )
            )
        }
    }

    @Test
    fun oneLaterRequestCoexistsWithTheActiveCursor() {
        val value =
            position.copy(
                after = "opaque-cursor",
                unresolved = true,
                requestedGeneration = requested,
            )
        assertTrue(validCollaborationHistoryRecoveryPosition(value, 20))
        assertFalse(
            validCollaborationHistoryRecoveryPosition(value.copy(requestedGeneration = active), 20)
        )
    }

    @Test
    fun completedAttemptCannotHideAnUnvisitedCursorOrRequest() {
        assertFalse(
            validCollaborationHistoryRecoveryPosition(
                position.copy(complete = true, after = "cursor"),
                20,
            )
        )
        assertFalse(
            validCollaborationHistoryRecoveryPosition(
                position.copy(complete = true, requestedGeneration = requested),
                20,
            )
        )
    }

    @Test
    fun cursorEnvelopeIsBoundedWithoutDependingOnStorageEncoding() {
        for (cursor in listOf("", " ", "\n", "x".repeat(201))) {
            assertFalse(
                validCollaborationHistoryRecoveryPosition(position.copy(after = cursor), 20)
            )
        }
        assertTrue(
            validCollaborationHistoryRecoveryPosition(position.copy(after = "x".repeat(200)), 20)
        )
    }

    @Test
    fun recoveryCannotBeAttachedToBrowseProgress() {
        assertFalse(
            validCollaborationHistoryProgress(progress.copy(incoming = null, recovery = position))
        )
        assertTrue(validCollaborationHistoryProgress(progress.copy(recovery = position)))
    }
}
