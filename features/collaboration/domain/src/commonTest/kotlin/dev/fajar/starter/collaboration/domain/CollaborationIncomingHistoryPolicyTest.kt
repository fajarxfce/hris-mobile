package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationIncomingHistoryPolicyTest {
    private val sender = "@original:example.test"
    private val peer = "@peer:example.test"
    private val original =
        CollaborationHistoryChange.Text(
            "\$original",
            sender,
            "00000000-0000-4000-8000-000000000001",
            100,
            "Fictional original",
            "a".repeat(64),
        )
    private val edit =
        CollaborationMessageEdit(
            "\$edit",
            sender,
            original.eventId,
            200,
            "Fictional edit",
            "b".repeat(64),
            originalLogicalId = original.logicalId,
        )

    @Test
    fun originalRetainsExactIdentityHashAndReplyFields() {
        val value = original.copy(replyToEventId = "\$quoted", forwarded = true)
        val result = retain(source(value))
        assertEquals(listOf(value), result.originals)
        assertTrue(result.replacements.isEmpty())
    }

    @Test
    fun sameVisibleTextNeverSubstitutesForTheCompleteContentHash() {
        val first = retain(source(original))
        val second = retain(source(original.copy(contentHash = "c".repeat(64))))
        assertNotEquals(first, second)
        assertEquals(
            original.text,
            (second.originals.single() as CollaborationHistoryChange.Text).text,
        )
    }

    @Test
    fun contradictoryOuterOriginalIdentityCannotReachStorage() {
        val event = source(original)
        for (bad in
            listOf(
                event.copy(eventId = "\$other"),
                event.copy(sender = peer),
                event.copy(timestamp = 101),
                event.copy(type = "m.room.encrypted"),
            )) {
            assertIs<AppResult.Failed>(planCollaborationIncomingHistory(bad))
        }
    }

    @Test
    fun malformedOriginalAndMetadataRemainExplicitFailures() {
        for (bad in
            listOf(
                source(original.copy(text = "")),
                source(original.copy(contentHash = "invalid")),
                source(original).copy(eventId = "invalid"),
                source(original).copy(sender = "invalid"),
                source(original).copy(timestamp = -1),
                source(original).copy(type = "m.room.message\n"),
            )) {
            assertIs<AppResult.Failed>(planCollaborationIncomingHistory(bad))
        }
    }

    @Test
    fun missingEditTargetRequestsExactlyItsOriginal() {
        assertEquals(
            CollaborationIncomingHistoryPlan.AcquireTarget(original.eventId),
            plan(replacement()),
        )
    }

    @Test
    fun replacementRetainsOriginalBeforeItsSeparateRevision() {
        val result = retain(replacement(), source(original))
        assertEquals(listOf(original), result.originals)
        assertEquals(listOf(edit), result.replacements)
        assertTrue(result.originals.none { it.eventId == edit.eventId })
    }

    @Test
    fun changedReplacementContentHashIsPreservedIndependently() {
        val changed = edit.copy(contentHash = "d".repeat(64))
        val result = retain(replacement(changed), source(original))
        assertEquals(changed, result.replacements.single())
        assertEquals(
            original.contentHash,
            (result.originals.single() as CollaborationHistoryChange.Text).contentHash,
        )
    }

    @Test
    fun contradictoryReplacementEnvelopeAndSelfTargetAreRejected() {
        val event = replacement()
        for (bad in
            listOf(
                event.copy(sender = peer),
                event.copy(eventId = "\$different"),
                event.copy(timestamp = 201),
                event.copy(type = "m.room.encrypted"),
                replacement(edit.copy(targetEventId = edit.eventId)),
            )) {
            assertIs<AppResult.Failed>(planCollaborationIncomingHistory(bad))
        }
    }

    @Test
    fun existingOriginalUsesLocalFactsWithoutRequestingAnotherRead() {
        val local = local()
        val result =
            assertIs<CollaborationIncomingHistoryPlan.Retain>(plan(replacement(), local = local))
        assertTrue(result.originals.isEmpty())
        assertEquals(listOf(edit), result.replacements)
    }

    @Test
    fun incoherentLocalAliasCannotAdmitAReplacement() {
        val local = local()
        for (bad in
            listOf(
                local.copy(fact = local.fact.copy(sender = peer)),
                local.copy(fact = local.fact.copy(eventId = "\$missing")),
                local.copy(fact = local.fact.copy(timestamp = 99)),
                local.copy(fact = local.fact.copy(timestamp = null)),
                local.copy(fact = local.fact.copy(redacted = true)),
            )) {
            assertIs<AppResult.Failed>(
                planCollaborationIncomingHistory(replacement(), localOriginal = bad)
            )
        }
    }

    @Test
    fun knownRedactedOriginalCanRetainIneligibleReplacementIdentity() {
        val local = local()
        val redacted =
            local.copy(
                message = local.message.copy(text = null, redacted = true),
                fact = local.fact.copy(redacted = true),
            )
        val result =
            assertIs<CollaborationIncomingHistoryPlan.Retain>(plan(replacement(), local = redacted))
        assertTrue(result.originals.isEmpty())
        assertEquals(listOf(edit), result.replacements)
        assertFalse(acceptsCollaborationMessageEdit(redacted.message, redacted.fact, edit))
    }

    @Test
    fun ineligiblePeerEditKeepsItsOwnEvidenceWithoutForgingTheOriginalSender() {
        val foreign = edit.copy(sender = peer)
        val result = retain(replacement(foreign), source(original))
        assertEquals(sender, result.originals.single().sender)
        assertEquals(peer, result.replacements.single().sender)
        val local = local()
        assertFalse(acceptsCollaborationMessageEdit(local.message, local.fact, foreign))
    }

    @Test
    fun extraOrWrongTargetsAreNotSilentlyAccepted() {
        assertIs<AppResult.Failed>(
            planCollaborationIncomingHistory(source(original), source(original))
        )
        assertIs<AppResult.Failed>(
            planCollaborationIncomingHistory(
                replacement(),
                source(original.copy(eventId = "\$other")),
            )
        )
        assertIs<AppResult.Failed>(
            planCollaborationIncomingHistory(replacement(), source(original), local())
        )
        assertIs<AppResult.Failed>(
            planCollaborationIncomingHistory(redaction(), localOriginal = local())
        )
    }

    @Test
    fun aReplacementTargetNeverStartsARecursiveRead() {
        val nested = edit.copy(eventId = original.eventId, targetEventId = "\$older")
        assertEquals(
            CollaborationIncomingHistoryPlan.Unsupported,
            plan(replacement(), replacement(nested)),
        )
    }

    @Test
    fun unavailableOriginalStaysATombstoneAndDoesNotConfirmTheEdit() {
        val result =
            assertIs<CollaborationIncomingHistoryPlan.MissingOriginal>(
                plan(replacement(), redacted())
            )
        assertEquals(
            CollaborationHistoryChange.Redacted(original.eventId, sender),
            result.tombstone,
        )
    }

    @Test
    fun sourceOrTargetMissingKeysRetainTheirExactUnresolvedIdentity() {
        for (reason in CollaborationEventDecryptionFailure.entries) {
            val pending =
                source(original)
                    .copy(
                        type = "m.room.encrypted",
                        content = CollaborationAcquiredEventContent.Unresolved(reason),
                    )
            assertEquals(
                CollaborationIncomingHistoryPlan.AwaitDecryption(original.eventId, reason),
                plan(pending),
            )
            assertEquals(
                CollaborationIncomingHistoryPlan.AwaitDecryption(original.eventId, reason),
                plan(replacement(), pending),
            )
            assertEquals(
                CollaborationIncomingHistoryPlan.AwaitDecryption(original.eventId, reason),
                plan(redaction(), pending),
            )
        }
    }

    @Test
    fun redactionTriggerRequiresConfirmationAndUsesTheOriginalSender() {
        assertEquals(
            CollaborationIncomingHistoryPlan.AcquireTarget(original.eventId),
            plan(redaction()),
        )
        val result = retain(redaction(), redacted())
        assertEquals(
            listOf(CollaborationHistoryChange.Redacted(original.eventId, sender)),
            result.originals,
        )
        assertTrue(result.replacements.isEmpty())
        assertNotEquals(peer, result.originals.single().sender)
    }

    @Test
    fun anEarlierConfirmedRedactionStillProvesTheTargetIsDeleted() {
        val target =
            redacted().copy(content = CollaborationAcquiredEventContent.Redacted("\$earlier"))
        assertEquals(retain(redaction(), redacted()), retain(redaction(), target))
    }

    @Test
    fun anUnredactedTargetDoesNotBecomeATombstoneFromTheTriggerAlone() {
        assertEquals(
            CollaborationIncomingHistoryPlan.AwaitRedaction(original.eventId),
            plan(redaction(), source(original)),
        )
    }

    @Test
    fun directlyAcquiredRedactedMessagesRemainOriginalTombstones() {
        assertEquals(
            listOf(CollaborationHistoryChange.Redacted(original.eventId, sender)),
            retain(redacted()).originals,
        )
        assertIs<AppResult.Failed>(
            planCollaborationIncomingHistory(
                redacted()
                    .copy(content = CollaborationAcquiredEventContent.Redacted(original.eventId))
            )
        )
    }

    @Test
    fun unsupportedEventsAndTargetsDoNotBecomeMessagesOrRecursiveTargets() {
        val unsupported =
            source(original)
                .copy(
                    type = "m.room.topic",
                    content = CollaborationAcquiredEventContent.Unsupported,
                )
        assertEquals(CollaborationIncomingHistoryPlan.Unsupported, plan(unsupported))
        assertEquals(CollaborationIncomingHistoryPlan.Unsupported, plan(replacement(), unsupported))
        assertEquals(CollaborationIncomingHistoryPlan.Unsupported, plan(redaction(), unsupported))
    }

    @Test
    fun diagnosticStringsDoNotExposeBodiesOrProviderIdentities() {
        for (value in
            listOf(
                plan(source(original)),
                plan(replacement()),
                plan(replacement(), redacted()),
                local(),
            )) {
            assertFalse(value.toString().contains("Fictional"))
            assertFalse(value.toString().contains("\$original"))
            assertFalse(value.toString().contains(sender))
        }
    }

    private fun source(value: CollaborationHistoryChange.Text) =
        CollaborationAcquiredEvent(
            value.eventId,
            value.sender,
            value.timestamp,
            "m.room.message",
            CollaborationAcquiredEventContent.Original(value),
        )

    private fun replacement(value: CollaborationMessageEdit = edit) =
        CollaborationAcquiredEvent(
            value.eventId,
            value.sender,
            value.timestamp,
            "m.room.message",
            CollaborationAcquiredEventContent.Replacement(value),
        )

    private fun redaction() =
        CollaborationAcquiredEvent(
            "\$delete",
            peer,
            300,
            "m.room.redaction",
            CollaborationAcquiredEventContent.Redaction(original.eventId),
        )

    private fun redacted() =
        CollaborationAcquiredEvent(
            original.eventId,
            sender,
            original.timestamp,
            "m.room.encrypted",
            CollaborationAcquiredEventContent.Redacted("\$delete"),
        )

    private fun local() =
        CollaborationIncomingOriginal(
            requireNotNull(mergeCollaborationHistoryText(null, original)),
            CollaborationHistoryEventFact(original.eventId, sender, original.timestamp, false),
        )

    private fun plan(
        event: CollaborationAcquiredEvent,
        target: CollaborationAcquiredEvent? = null,
        local: CollaborationIncomingOriginal? = null,
    ) =
        assertIs<AppResult.Success<CollaborationIncomingHistoryPlan>>(
                planCollaborationIncomingHistory(event, target, local)
            )
            .value

    private fun retain(
        event: CollaborationAcquiredEvent,
        target: CollaborationAcquiredEvent? = null,
    ) = assertIs<CollaborationIncomingHistoryPlan.Retain>(plan(event, target))
}
