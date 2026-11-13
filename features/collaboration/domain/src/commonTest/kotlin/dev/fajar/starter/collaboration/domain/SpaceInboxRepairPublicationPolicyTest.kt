package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class SpaceInboxRepairPublicationPolicyTest {
    private fun id(value: Int) = "00000000-0000-0000-0000-" + value.toString().padStart(12, '0')

    private val initial = CollaborationSpaceInboxRepairProgress(id(1), id(2), null, null)

    @Test
    fun initialPublicationRetainsFreshDemandWithoutInventingAnActivePass() {
        assertTrue(
            validCollaborationSpaceInboxRepairPublication(
                CollaborationSpaceInboxRepairPublication(null, initial)
            )
        )
        assertFalse(
            validCollaborationSpaceInboxRepairPublication(
                CollaborationSpaceInboxRepairPublication(
                    null,
                    initial.copy(pass = CollaborationSpaceInboxRepairPass(id(2), null)),
                )
            )
        )
    }

    @Test
    fun laterRequestPreservesCapturedPassAndCompletedDemand() {
        val before =
            initial.copy(
                completedDemand = id(3),
                pass = CollaborationSpaceInboxRepairPass(id(2), "cursor"),
            )
        val after = before.copy(generation = id(4), latestDemand = id(5))
        assertTrue(
            validCollaborationSpaceInboxRepairPublication(
                CollaborationSpaceInboxRepairPublication(before, after)
            )
        )
        for (changed in
            listOf(
                after.copy(pass = null),
                after.copy(completedDemand = null),
                after.copy(pass = before.pass?.copy(after = null)),
            )) {
            assertFalse(
                validCollaborationSpaceInboxRepairPublication(
                    CollaborationSpaceInboxRepairPublication(before, changed)
                )
            )
        }
    }

    @Test
    fun noopOrReusedGenerationCannotArchiveWithoutFreshDemand() {
        assertFalse(
            validCollaborationSpaceInboxRepairPublication(
                CollaborationSpaceInboxRepairPublication(initial, initial)
            )
        )
        assertFalse(
            validCollaborationSpaceInboxRepairPublication(
                CollaborationSpaceInboxRepairPublication(initial, initial.copy(generation = id(6)))
            )
        )
        assertFalse(
            validCollaborationSpaceInboxRepairPublication(
                CollaborationSpaceInboxRepairPublication(
                    initial,
                    initial.copy(latestDemand = id(5)),
                )
            )
        )
    }

    @Test
    fun retiredOrActiveDemandCannotBeReusedForAnotherTerminalRecord() {
        val before =
            initial.copy(
                latestDemand = id(7),
                completedDemand = id(3),
                pass = CollaborationSpaceInboxRepairPass(id(8), null),
            )
        for (demand in listOf(id(3), id(8))) {
            assertFalse(
                validCollaborationSpaceInboxRepairPublication(
                    CollaborationSpaceInboxRepairPublication(
                        before,
                        before.copy(generation = id(9), latestDemand = demand),
                    )
                )
            )
        }
    }

    @Test
    fun malformedCoordinatesAndProgressAreRejected() {
        for (value in
            listOf(
                initial.copy(generation = ""),
                initial.copy(latestDemand = ""),
                initial.copy(generation = "x".repeat(257)),
            )) {
            assertFalse(
                validCollaborationSpaceInboxRepairPublication(
                    CollaborationSpaceInboxRepairPublication(null, value)
                )
            )
        }
        val malformed =
            initial.copy(pass = CollaborationSpaceInboxRepairPass(id(2), "x".repeat(4097)))
        assertFalse(
            validCollaborationSpaceInboxRepairPublication(
                CollaborationSpaceInboxRepairPublication(
                    malformed,
                    malformed.copy(generation = id(9), latestDemand = id(5)),
                )
            )
        )
    }

    @Test
    fun completedIdlePassCanReceiveFreshWorkWithoutReopeningTheOldPass() {
        val idle = initial.copy(completedDemand = id(2))
        val after = idle.copy(generation = id(9), latestDemand = id(5))
        val publication = CollaborationSpaceInboxRepairPublication(idle, after)
        assertTrue(validCollaborationSpaceInboxRepairPublication(publication))
        assertFalse(publication.toString().contains(id(5)))
    }
}
