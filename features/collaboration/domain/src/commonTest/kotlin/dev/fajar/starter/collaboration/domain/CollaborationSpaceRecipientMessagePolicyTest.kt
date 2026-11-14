package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

class CollaborationSpaceRecipientMessagePolicyTest {
    private val access =
        CollaborationSpaceLocalAccess("login", "https://hris.example.test/", id(1), id(2))
    private val original =
        CollaborationSpaceRecipientSubmission(
            CollaborationSpaceRecipientDraft(
                id(3),
                id(4),
                "  exact\nmessage  ",
                Instant.parse("2026-11-14T00:00:00.123456789Z"),
            ),
            id(5),
            Instant.parse("2026-11-14T00:00:01.987654321Z"),
        )
    private val intent = collaborationSpaceRecipientMessageIntent(access, original, id(6))
    private val record =
        CollaborationSpaceMessageRecord(intent, 0, CollaborationMessageState.Queued)

    @Test
    fun conversionRetainsExactCapturedFactsAndSeparatelySuppliedTarget() {
        assertEquals(original.id, intent.id)
        assertEquals(id(6), intent.conversationId)
        assertEquals(original.original.text, intent.text)
        assertEquals(original.submittedAt, intent.createdAt)
        assertEquals(
            CollaborationSpaceRecipientOrigin(id(3), original.original.savedAt, id(5)),
            intent.recipientOrigin,
        )
        assertTrue(matchesCollaborationSpaceRecipientSubmission(access, record, original))
    }

    @Test
    fun canonicalLegacyOriginalRemainsValidButCannotProveRecipientReplay() {
        val legacy = intent.copy(recipientOrigin = null)
        assertTrue(validCollaborationSpaceMessageIntent(access, legacy))
        assertFalse(
            matchesCollaborationSpaceRecipientSubmission(
                access,
                record.copy(intent = legacy),
                original,
            )
        )
    }

    @Test
    fun changedPeerBodyTimesOrEditorGenerationCannotProveReplay() {
        val draft = original.original
        for (changed in
            listOf(
                original.copy(original = draft.copy(peerAccountId = id(7))),
                original.copy(original = draft.copy(text = draft.text.trim())),
                original.copy(original = draft.copy(savedAt = draft.savedAt + 1.nanoseconds)),
                original.copy(original = draft.copy(revision = id(8))),
                original.copy(nextRevision = id(9)),
                original.copy(submittedAt = original.submittedAt + 1.nanoseconds),
            )) assertFalse(matchesCollaborationSpaceRecipientSubmission(access, record, changed))
    }

    @Test
    fun invalidOrSelfRecipientAndRepeatedGenerationAreRejected() {
        val origin = assertNotNull(intent.recipientOrigin)
        for (invalid in
            listOf(
                origin.copy(peerAccountId = access.accountId),
                origin.copy(peerAccountId = "invalid"),
                origin.copy(nextRevision = intent.id),
                origin.copy(nextRevision = origin.peerAccountId),
                origin.copy(nextRevision = "invalid"),
                origin.copy(draftSavedAt = Instant.fromEpochMilliseconds(-1)),
            )) assertFalse(
            validCollaborationSpaceMessageIntent(access, intent.copy(recipientOrigin = invalid))
        )
    }

    @Test
    fun plainRecipientProofCannotDescribeAReplyOrForward() {
        assertFalse(
            validCollaborationSpaceMessageIntent(access, intent.copy(replyToEventId = "\$reply"))
        )
        assertFalse(validCollaborationSpaceMessageIntent(access, intent.copy(forwarded = true)))
    }

    @Test
    fun localReauthenticationPreservesProofButAnotherOwnerDoesNot() {
        assertTrue(
            matchesCollaborationSpaceRecipientSubmission(
                access.copy(sessionId = "new-login"),
                record,
                original,
            )
        )
        for (other in
            listOf(
                access.copy(accountId = id(7)),
                access.copy(spaceId = id(8)),
                access.copy(audience = "https://another.example.test/"),
            )) assertFalse(matchesCollaborationSpaceRecipientSubmission(other, record, original))
    }

    @Test
    fun terminalCancellationProvesRetentionWithoutClaimingDelivery() {
        assertTrue(
            matchesCollaborationSpaceRecipientSubmission(
                access,
                record.copy(state = CollaborationMessageState.Cancelled),
                original,
            )
        )
        assertFalse(
            matchesCollaborationSpaceRecipientSubmission(
                access,
                record.copy(revision = 1),
                original,
            )
        )
    }

    @Test
    fun diagnosticStringsDoNotExposePeerBodyOrGenerations() {
        assertEquals(
            "CollaborationSpaceRecipientOrigin([redacted])",
            intent.recipientOrigin.toString(),
        )
        assertEquals("CollaborationSpaceMessageIntent([redacted])", intent.toString())
    }

    private fun id(value: Int) = "aaaaaaaa-0000-4000-8000-" + value.toString().padStart(12, '0')
}
