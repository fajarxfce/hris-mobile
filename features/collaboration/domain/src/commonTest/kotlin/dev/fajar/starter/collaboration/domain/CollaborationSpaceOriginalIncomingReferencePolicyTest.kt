package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceOriginalIncomingReferencePolicyTest {
    @Test
    fun retainedTargetsAndUnsupportedEventsResolveTheirOrderedSources() {
        val f = SpaceOriginalReferenceCompletionFixture()
        f.outcome(
            0,
            CollaborationIncomingHistoryOutcome.Retained(listOf("\$redacted-target"), emptyList()),
        )
        f.outcome(1, CollaborationIncomingHistoryOutcome.Unsupported)
        assertEquals(
            CollaborationSpaceIncomingReferencePlan(f.references.events, 0, false, false),
            success(f),
        )
        assertNotEquals("\$redacted-target", success(f).resolved.first().event.eventId)
    }

    @Test
    fun missingKeysRedactionAndOriginalTargetsStayUnresolvedAtProviderEnd() {
        for (outcome in
            listOf(
                CollaborationIncomingHistoryOutcome.AwaitDecryption(
                    "\$target",
                    CollaborationEventDecryptionFailure.UnableToDecrypt,
                ),
                CollaborationIncomingHistoryOutcome.AwaitRedaction("\$target"),
                CollaborationIncomingHistoryOutcome.MissingOriginal("\$target"),
            )) {
            val f = SpaceOriginalReferenceCompletionFixture()
            f.outcome(0, outcome)
            assertNull(f.references.progress!!.next)
            assertEquals(
                CollaborationSpaceIncomingReferencePlan(
                    f.references.events.drop(1),
                    1,
                    false,
                    true,
                ),
                success(f),
            )
        }
    }

    @Test
    fun differentRuntimeRoomCompanyAndPhysicalDeviceCannotCompleteThisBatch() {
        val f = SpaceOriginalReferenceCompletionFixture()
        val batch = assertNotNull(f.batch)
        for (changed in
            listOf(
                batch.copy(runtime = batch.runtime.copy(deviceId = f.base.id(999))),
                batch.copy(
                    runtime =
                        batch.runtime.copy(
                            owner = batch.runtime.owner.copy(spaceId = f.base.id(999))
                        )
                ),
                batch.copy(
                    room =
                        batch.room.copy(
                            traversal = batch.room.traversal.copy(roomId = "!other:example.test")
                        )
                ),
                batch.copy(
                    room =
                        batch.room.copy(
                            traversal = batch.room.traversal.copy(deviceId = f.runtime.deviceId)
                        )
                ),
                batch.copy(items = batch.items.reversed()),
                batch.copy(items = batch.items.drop(1)),
            )) {
            f.batch = changed
            assertIs<AppResult.Failed>(plan(f))
        }
    }

    @Test
    fun providerQualifiedGroupProgressIsNeverCompanyProgress() {
        val f = SpaceOriginalReferenceCompletionFixture()
        f.references =
            f.references.copy(
                progress =
                    f.references.progress!!.copy(
                        provider =
                            CollaborationHistoryProviderIdentity(
                                f.base.position.companyId,
                                f.runtime.deviceId,
                            )
                    )
            )
        assertIs<AppResult.Failed>(plan(f))
    }

    @Test
    fun absentUnattemptedAndMalformedOutcomesCannotResolveSources() {
        val f = SpaceOriginalReferenceCompletionFixture()
        f.batch = null
        assertIs<AppResult.Failed>(plan(f))
        f.acquired()
        for (outcome in
            listOf(
                CollaborationIncomingHistoryOutcome.Retained(emptyList(), emptyList()),
                CollaborationIncomingHistoryOutcome.Retained(listOf("bad"), emptyList()),
                CollaborationIncomingHistoryOutcome.Retained(listOf("\$same"), listOf("\$same")),
                CollaborationIncomingHistoryOutcome.Retained(
                    List(17) { "\$target-$it" },
                    emptyList(),
                ),
                CollaborationIncomingHistoryOutcome.AwaitDecryption(
                    "bad",
                    CollaborationEventDecryptionFailure.UnableToDecrypt,
                ),
                CollaborationIncomingHistoryOutcome.AwaitRedaction("bad"),
                CollaborationIncomingHistoryOutcome.MissingOriginal("bad"),
            )) {
            f.outcome(0, outcome)
            assertIs<AppResult.Failed>(plan(f))
        }
    }

    @Test
    fun finalEmptyPageNeedsNoBatchAndRetainsEarlierRetry() {
        val f = SpaceOriginalReferenceCompletionFixture()
        f.references =
            f.references.copy(
                events = emptyList(),
                progress =
                    f.references.progress!!.copy(
                        incoming =
                            CollaborationIncomingHistoryPosition("start", null, "seek", 2, true)
                    ),
            )
        assertIs<AppResult.Failed>(plan(f))
        f.batch = null
        assertEquals(
            CollaborationSpaceIncomingReferencePlan(emptyList(), 0, false, true),
            success(f),
        )
    }

    @Test
    fun laterAppendsCannotExtendTheCapturedFinitePass() {
        val f = SpaceOriginalReferenceCompletionFixture()
        val progress =
            f.references.progress!!.copy(
                references = 50,
                incoming = CollaborationIncomingHistoryPosition("start", null, "seek", 20),
            )
        f.references =
            CollaborationHistoryReferences(progress, (16 until 20).map(f.pending::reference), null)
        f.acquired()
        assertEquals(4, success(f).resolved.size)
        assertFalse(success(f).more)
        f.references = f.references.copy(events = (16 until 21).map(f.pending::reference))
        f.acquired()
        assertIs<AppResult.Failed>(plan(f))
    }

    @Test
    fun completeSixteenReferencePageRetainsItsNextSeekAndPriorRetry() {
        val f = SpaceOriginalReferenceCompletionFixture()
        val progress =
            f.references.progress!!.copy(
                references = 40,
                incoming = CollaborationIncomingHistoryPosition("start", null, "seek", 40, true),
            )
        f.references =
            CollaborationHistoryReferences(
                progress,
                (16 until 32).map(f.pending::reference),
                "next-seek",
            )
        f.acquired()
        assertEquals(
            CollaborationSpaceIncomingReferencePlan(f.references.events, 0, true, true),
            success(f),
        )
    }

    @Test
    fun absentProgressNonPendingPhaseAndOversizedSourceAreRejected() {
        val f = SpaceOriginalReferenceCompletionFixture()
        assertIs<AppResult.Failed>(
            planCollaborationSpaceOriginalIncomingReferences(
                f.runtime,
                f.pending.room,
                f.references.copy(progress = null),
                f.batch,
            )
        )
        assertIs<AppResult.Failed>(
            planCollaborationSpaceOriginalIncomingReferences(
                f.runtime,
                f.pending.room.copy(
                    captured =
                        f.pending.room.captured.copy(
                            phase = CollaborationSpaceIncomingPhase.Provider
                        )
                ),
                f.references,
                f.batch,
            )
        )
        assertIs<AppResult.Failed>(
            planCollaborationSpaceOriginalIncomingReferences(
                f.runtime,
                f.pending.room,
                f.references.copy(events = List(17) { f.pending.reference(it) }),
                f.batch,
            )
        )
    }

    private fun plan(f: SpaceOriginalReferenceCompletionFixture) =
        planCollaborationSpaceOriginalIncomingReferences(
            f.runtime,
            f.pending.room,
            f.references,
            f.batch,
        )

    private fun success(f: SpaceOriginalReferenceCompletionFixture) =
        assertIs<AppResult.Success<CollaborationSpaceIncomingReferencePlan>>(plan(f)).value
}
