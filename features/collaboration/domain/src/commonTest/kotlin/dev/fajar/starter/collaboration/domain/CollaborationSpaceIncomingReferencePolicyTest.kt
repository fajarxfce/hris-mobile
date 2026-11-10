package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceIncomingReferencePolicyTest {
    @Test
    fun retainedAndUnsupportedFinishOnlyTheirExactSources() {
        val f = SpaceIncomingReferencesFixture()
        val values = f.batch!!.items.toMutableList()
        values[0] = values[0].copy(outcome = CollaborationIncomingHistoryOutcome.Unsupported)
        f.batch = f.batch!!.copy(items = values)
        val result = plan(f)
        assertEquals(f.source.events, result.resolved)
        assertEquals(0, result.unresolved)
        assertTrue(result.more)
        assertFalse(result.retryRequired)
    }

    @Test
    fun keyRedactionAndMissingOriginalRemainPendingAtProviderEnd() {
        val f = SpaceIncomingReferencesFixture()
        f.source = f.source.copy(progress = f.source.progress!!.copy(next = null))
        val values = f.batch!!.items.toMutableList()
        values[0] =
            values[0].copy(
                outcome =
                    CollaborationIncomingHistoryOutcome.AwaitDecryption(
                        "\$target",
                        CollaborationEventDecryptionFailure.UnableToDecrypt,
                    )
            )
        values[1] =
            values[1].copy(outcome = CollaborationIncomingHistoryOutcome.AwaitRedaction("\$target"))
        values[2] =
            values[2].copy(
                outcome = CollaborationIncomingHistoryOutcome.MissingOriginal("\$target")
            )
        f.batch = f.batch!!.copy(items = values)
        val result = plan(f)
        assertEquals(f.source.events.drop(3), result.resolved)
        assertEquals(3, result.unresolved)
        assertTrue(result.retryRequired)
    }

    @Test
    fun aRedactionSourceCanDifferFromItsRetainedOriginalTarget() {
        val f = SpaceIncomingReferencesFixture()
        val values = f.batch!!.items.toMutableList()
        values[0] =
            values[0].copy(
                outcome =
                    CollaborationIncomingHistoryOutcome.Retained(listOf("\$target"), emptyList())
            )
        f.batch = f.batch!!.copy(items = values)
        assertEquals(f.source.events.first(), plan(f).resolved.first())
        assertNotEquals("\$target", plan(f).resolved.first().event.eventId)
    }

    @Test
    fun logicalAndPhysicalBatchChangesCannotResolveCurrentReferences() {
        val f = SpaceIncomingReferencesFixture()
        val b = f.batch!!
        for (changed in
            listOf(
                b.copy(runtime = b.runtime.copy(deviceId = f.provider.queue.id(777))),
                b.copy(
                    runtime =
                        b.runtime.copy(
                            owner = b.runtime.owner.copy(spaceId = f.provider.queue.id(777))
                        )
                ),
                b.copy(conversationId = f.provider.queue.id(777)),
                b.copy(roomId = "!other:example.test"),
                b.copy(provider = b.provider.copy(deviceId = f.provider.queue.id(777))),
                b.copy(provider = b.provider.copy(companyId = f.provider.queue.id(777))),
                b.copy(items = b.items.reversed()),
                b.copy(items = b.items.drop(1)),
            )) {
            f.batch = changed
            assertIs<AppResult.Failed>(
                planCollaborationSpaceIncomingReferences(f.access, f.key, f.source, f.batch)
            )
        }
    }

    @Test
    fun malformedFactsAndUnattemptedOrOversizedSourcesNeverResolve() {
        val f = SpaceIncomingReferencesFixture()
        val b = f.batch!!
        for (outcome in
            listOf(
                CollaborationIncomingHistoryOutcome.Retained(emptyList(), emptyList()),
                CollaborationIncomingHistoryOutcome.Retained(listOf("bad"), emptyList()),
                CollaborationIncomingHistoryOutcome.Retained(listOf("\$same"), listOf("\$same")),
                CollaborationIncomingHistoryOutcome.Retained(
                    List(17) { "\$event-$it" },
                    emptyList(),
                ),
                CollaborationIncomingHistoryOutcome.AwaitRedaction("bad"),
            )) {
            f.batch =
                b.copy(
                    items = b.items.toMutableList().also { it[0] = it[0].copy(outcome = outcome) }
                )
            assertIs<AppResult.Failed>(
                planCollaborationSpaceIncomingReferences(f.access, f.key, f.source, f.batch)
            )
        }
        assertIs<AppResult.Failed>(
            planCollaborationSpaceIncomingReferences(f.access, f.key, f.source, null)
        )
    }

    @Test
    fun pendingRetrySurvivesTheFinalEmptyPage() {
        val f = SpaceIncomingReferencesFixture()
        f.source =
            f.source.copy(
                progress =
                    f.source.progress!!.copy(
                        incoming =
                            CollaborationIncomingHistoryPosition("head", null, "after-16", 20, true)
                    ),
                events = emptyList(),
                after = null,
            )
        f.batch = null
        val result = plan(f)
        assertTrue(result.retryRequired)
        assertFalse(result.more)
        assertTrue(result.resolved.isEmpty())
    }

    @Test
    fun appendedReferencesCannotExtendTheFiniteSixteenItemPass() {
        val f = SpaceIncomingReferencesFixture()
        val progress =
            f.source.progress!!.copy(
                references = 40,
                incoming = CollaborationIncomingHistoryPosition("head", null, "after-16", 20),
            )
        val page =
            f.source.copy(
                progress = progress,
                events =
                    (16L..31L).map { n ->
                        CollaborationHistoryReference(
                            n,
                            f.source.events.first().event.copy(eventId = "\$event-$n"),
                        )
                    },
                after = "after-32",
            )
        val bounded = assertNotNull(boundedCollaborationIncomingReferences(page, 16))
        assertEquals(listOf(16L, 17L, 18L, 19L), bounded.events.map { it.sequence })
        assertNull(bounded.after)
        assertNull(boundedCollaborationIncomingReferences(page))
    }

    @Test
    fun invalidLimitsAndCrossingTheCapturedReferenceCountAreRejected() {
        val f = SpaceIncomingReferencesFixture()
        assertNull(boundedCollaborationIncomingReferences(f.source, 0))
        assertNull(boundedCollaborationIncomingReferences(f.source, 21))
        f.source =
            f.source.copy(
                progress =
                    f.source.progress!!.copy(
                        incoming = CollaborationIncomingHistoryPosition("head", null, "after", 10)
                    )
            )
        assertIs<AppResult.Failed>(
            planCollaborationSpaceIncomingReferences(f.access, f.key, f.source, f.batch)
        )
    }

    private fun plan(f: SpaceIncomingReferencesFixture) =
        assertIs<AppResult.Success<CollaborationSpaceIncomingReferencePlan>>(
                planCollaborationSpaceIncomingReferences(f.access, f.key, f.source, f.batch)
            )
            .value
}
