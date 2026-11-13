package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceInboxSeedDemandPolicyTest {
    @Test
    fun startupCapturesEveryCurrentOriginalInStableOrder() {
        val f = SpaceInboxCommandFixture()
        val records =
            listOf(3, 1, 2).map { n ->
                f.queued().let { it.copy(intent = it.intent.copy(id = f.id(n))) }
            }
        val selected = success(collaborationSpaceInboxSeedDemand(f.access, null, records))
        assertEquals(listOf(f.id(1), f.id(2), f.id(3)), selected.map { it.id })
        assertTrue(
            success(collaborationSpaceInboxSeedDemand(f.access, null, emptyList())).isEmpty()
        )
    }

    @Test
    fun unchangedOrReorderedInventoriesNeverRetryEvenWhenRecoveryIsPending() {
        val f = SpaceInboxCommandFixture()
        val records =
            listOf(f.queued(), f.sent().let { it.copy(intent = it.intent.copy(id = f.id(2))) })
        for (retry in listOf(false, true)) {
            assertTrue(
                success(
                        collaborationSpaceInboxSeedDemand(
                            f.access,
                            records,
                            records.reversed(),
                            retry,
                        )
                    )
                    .isEmpty()
            )
        }
    }

    @Test
    fun ordinaryDeliveryProgressSeedsOnlyANewOriginal() {
        val f = SpaceInboxCommandFixture()
        val next = f.queued().let { it.copy(intent = it.intent.copy(id = f.id(2))) }
        assertEquals(
            listOf(next.intent),
            success(
                collaborationSpaceInboxSeedDemand(
                    f.access,
                    listOf(f.queued()),
                    listOf(f.sent(), next),
                )
            ),
        )
        assertTrue(
            success(
                    collaborationSpaceInboxSeedDemand(
                        f.access,
                        listOf(f.queued()),
                        listOf(f.sent()),
                    )
                )
                .isEmpty()
        )
    }

    @Test
    fun laterExternalOriginalChangesCanResumeAllUnfinishedCurrentOriginals() {
        val f = SpaceInboxCommandFixture()
        val previous = listOf(f.queued())
        val next = f.queued().let { it.copy(intent = it.intent.copy(id = f.id(2))) }
        val selected =
            success(
                collaborationSpaceInboxSeedDemand(
                    f.access,
                    previous,
                    listOf(f.sent(), next),
                    retryCurrent = true,
                )
            )
        assertEquals(setOf(f.queued().intent, next.intent), selected.toSet())
        assertEquals(
            listOf(f.queued().intent),
            success(
                collaborationSpaceInboxSeedDemand(
                    f.access,
                    previous,
                    emptyList(),
                    retryCurrent = true,
                )
            ),
        )
    }

    @Test
    fun aJustObservedRetirementKeepsItsExactSourceIntent() {
        val f = SpaceInboxCommandFixture()
        for (prior in listOf(f.queued(), f.sent())) {
            assertEquals(
                listOf(prior.intent),
                success(collaborationSpaceInboxSeedDemand(f.access, listOf(prior), emptyList())),
            )
        }
    }

    @Test
    fun malformedOwnershipDuplicatesAndRewrittenImmutableIntentNeverSelectWork() {
        val f = SpaceInboxCommandFixture()
        val record = f.queued()
        val changed = record.copy(intent = record.intent.copy(text = "Rewritten original"))
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
            )) {
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(
                        collaborationSpaceInboxSeedDemand(f.access, before, after, true)
                    )
                    .failure
                    .kind,
            )
        }
    }

    @Test
    fun allTwoHundredFiftySixBoundedInputsAreDetachedWithoutTruncatingUnattemptedWork() {
        val f = SpaceInboxCommandFixture()
        val before =
            (1..128)
                .map { n -> f.queued().let { it.copy(intent = it.intent.copy(id = f.id(n))) } }
                .toMutableList()
        val after =
            (129..256)
                .map { n -> f.queued().let { it.copy(intent = it.intent.copy(id = f.id(n))) } }
                .toMutableList()
        val selected = success(collaborationSpaceInboxSeedDemand(f.access, before, after))
        assertEquals(256, selected.size)
        assertEquals((1..256).map { f.id(it) }, selected.map { it.id })
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxSeedDemand(f.access, null, after + before.first())
        )
        before.clear()
        after.clear()
        assertEquals(256, selected.size)
    }

    @Test
    fun editOnlyProgressCannotRetryOriginalSeeding() {
        val f = SpaceInboxCommandFixture()
        val sent = f.sent().state as CollaborationMessageState.Sent
        val target =
            CollaborationEditTarget(
                sent.device.roomId,
                sent.eventId,
                f.reference.intent.id,
                sent.origin!!.sender,
                "a".repeat(64),
                0,
                0,
                replyToEventId = f.reference.intent.replyToEventId,
                forwarded = f.reference.intent.forwarded,
            )
        val edit =
            f.queued().let { it.copy(intent = it.intent.copy(id = f.id(2), editTarget = target)) }
        val finished = edit.copy(revision = 3, state = sent)
        assertTrue(validCollaborationSpaceMessageRecord(f.access, edit))
        assertTrue(validCollaborationSpaceMessageRecord(f.access, finished))
        for (retry in listOf(false, true)) {
            assertTrue(
                success(
                        collaborationSpaceInboxSeedDemand(
                            f.access,
                            listOf(f.queued(), edit),
                            listOf(f.queued(), finished),
                            retry,
                        )
                    )
                    .isEmpty()
            )
            assertTrue(
                success(collaborationSpaceInboxSeedDemand(f.access, null, listOf(edit), retry))
                    .isEmpty()
            )
        }
    }

    private fun <T> success(value: AppResult<T>): T = assertIs<AppResult.Success<T>>(value).value
}
