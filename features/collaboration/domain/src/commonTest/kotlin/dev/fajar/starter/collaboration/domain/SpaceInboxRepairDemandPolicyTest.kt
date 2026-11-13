package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceInboxRepairDemandPolicyTest {
    @Test
    fun firstObservationRequestsOneFinitePassAndUnchangedEmptySnapshotsAreIdle() {
        val f = SpaceInboxCommandFixture()
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxRepairDemand(emptyList(), true)),
            collaborationSpaceInboxRepairDemand(f.access, null, emptyList()),
        )
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxRepairDemand(emptyList(), false)),
            collaborationSpaceInboxRepairDemand(f.access, emptyList(), emptyList()),
        )
    }

    @Test
    fun equalRecordsInDifferentEnumerationOrderDoNotCreateWork() {
        val f = SpaceInboxCommandFixture()
        val first = f.queued()
        val second = f.sent().let { it.copy(intent = it.intent.copy(id = f.id(2))) }
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxRepairDemand(emptyList(), false)),
            collaborationSpaceInboxRepairDemand(
                f.access,
                listOf(first, second),
                listOf(second, first),
            ),
        )
    }

    @Test
    fun nonterminalProgressDoesNotReadOrScanTheInbox() {
        val f = SpaceInboxCommandFixture()
        val sent = f.sent().state as CollaborationMessageState.Sent
        val queued = f.queued()
        val staging =
            queued.copy(
                revision = 1,
                state = CollaborationMessageState.Staging(sent.device, emptySet(), sent.origin),
            )
        val staged =
            queued.copy(
                revision = 2,
                state =
                    CollaborationMessageState.Staged(sent.device, sent.transactionId, sent.origin),
            )
        for ((before, after) in
            listOf(
                emptyList<CollaborationSpaceMessageRecord>() to listOf(queued),
                listOf(queued) to listOf(staging),
                listOf(staging) to listOf(staged),
            )) assertEquals(
            AppResult.Success(CollaborationSpaceInboxRepairDemand(emptyList(), false)),
            collaborationSpaceInboxRepairDemand(f.access, before, after),
        )
    }

    @Test
    fun changedTerminalAndObservedRetirementUseExactOriginalCoordinates() {
        val f = SpaceInboxCommandFixture()
        val target =
            CollaborationSpaceInboxRepairTarget(
                f.reference.intent.conversationId,
                f.reference.intent.id,
            )
        for (current in
            listOf(
                listOf(f.sent()),
                listOf(f.queued().copy(state = CollaborationMessageState.Cancelled)),
                emptyList(),
            )) {
            val result =
                assertIs<AppResult.Success<CollaborationSpaceInboxRepairDemand>>(
                        collaborationSpaceInboxRepairDemand(f.access, listOf(f.queued()), current)
                    )
                    .value
            assertEquals(listOf(target), result.targets)
            assertFalse(result.requiresScan)
        }
    }

    @Test
    fun malformedOwnershipDuplicatesAndRewrittenImmutableIntentAreRejected() {
        val f = SpaceInboxCommandFixture()
        val record = f.queued()
        val changed = record.copy(intent = record.intent.copy(text = "Different immutable body"))
        val foreign =
            record.copy(
                intent = record.intent.copy(owner = record.intent.owner.copy(spaceId = f.id(9)))
            )
        for ((before, after) in
            listOf(
                listOf(record) to listOf(changed),
                emptyList<CollaborationSpaceMessageRecord>() to listOf(foreign),
                listOf(record, record) to listOf(record),
                emptyList<CollaborationSpaceMessageRecord>() to listOf(record, record),
            )) assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(collaborationSpaceInboxRepairDemand(f.access, before, after))
                .failure
                .kind,
        )
    }

    @Test
    fun overflowKeepsBoundedTargetsAndCoalescesOneFinitePass() {
        val f = SpaceInboxCommandFixture()
        val before =
            (1..128).map { n -> f.queued().let { it.copy(intent = it.intent.copy(id = f.id(n))) } }
        val after =
            (129..256).map { n -> f.sent().let { it.copy(intent = it.intent.copy(id = f.id(n))) } }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceInboxRepairDemand>>(
                    collaborationSpaceInboxRepairDemand(f.access, before, after)
                )
                .value
        assertEquals(128, result.targets.size)
        assertEquals(
            after.map { it.intent.id }.toSet(),
            result.targets.map { it.messageId }.toSet(),
        )
        assertTrue(result.requiresScan)
    }

    @Test
    fun originalRepairDoesNotTreatAnEditOperationAsANewOriginal() {
        val f = SpaceInboxCommandFixture()
        val sent = f.sent().state as CollaborationMessageState.Sent
        val origin = assertNotNull(sent.origin)
        val target =
            CollaborationEditTarget(
                sent.device.roomId,
                sent.eventId,
                f.reference.intent.id,
                origin.sender,
                "a".repeat(64),
                0,
                0,
                replyToEventId = f.reference.intent.replyToEventId,
                forwarded = f.reference.intent.forwarded,
            )
        val edit =
            f.queued().let { it.copy(intent = it.intent.copy(id = f.id(2), editTarget = target)) }
        assertTrue(validCollaborationSpaceMessageRecord(f.access, edit))
        val completed = edit.copy(revision = 3, state = sent)
        assertTrue(validCollaborationSpaceMessageRecord(f.access, completed))
        for (current in listOf(listOf(completed), emptyList())) assertEquals(
            AppResult.Success(CollaborationSpaceInboxRepairDemand(emptyList(), false)),
            collaborationSpaceInboxRepairDemand(f.access, listOf(edit), current),
        )
    }

    @Test
    fun selectedCoordinatesDoNotAliasMutableSourceLists() {
        val f = SpaceInboxCommandFixture()
        val records = mutableListOf(f.sent())
        val result =
            assertIs<AppResult.Success<CollaborationSpaceInboxRepairDemand>>(
                    collaborationSpaceInboxRepairDemand(f.access, emptyList(), records)
                )
                .value
        records.clear()
        assertEquals(
            listOf(
                CollaborationSpaceInboxRepairTarget(
                    f.reference.intent.conversationId,
                    f.reference.intent.id,
                )
            ),
            result.targets,
        )
        assertFalse(result.requiresScan)
    }
}
