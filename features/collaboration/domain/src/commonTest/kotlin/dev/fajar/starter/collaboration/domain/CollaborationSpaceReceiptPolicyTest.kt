package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*
import kotlin.time.Instant

class CollaborationSpaceReceiptPolicyTest {
    private val access =
        CollaborationSpaceLocalAccess("login", "https://hris.example.test/", id(1), id(2))
    private val conversation = id(3)
    private val device =
        CollaborationMessageDevice(id(5), id(6), id(7), "!space:matrix.example.test")
    private val origin = CollaborationMessageOrigin("@employee:matrix.example.test", "a".repeat(64))
    private val original = receipt(1)
    private val staged =
        CollaborationSpaceMessageRecord(
            original.intent,
            2,
            CollaborationMessageState.Staged(device, original.transactionId, origin),
        )

    @Test
    fun originalReceiptSurvivesLocalReauthenticationWithoutAnAgeDeadline() {
        assertTrue(validCollaborationSpaceReceipt(access, conversation, original))
        assertTrue(
            validCollaborationSpaceReceipt(
                access.copy(sessionId = "renewed"),
                conversation,
                original,
            )
        )
        assertTrue(
            validCollaborationSpaceReceipt(
                access,
                conversation,
                original.copy(
                    intent = original.intent.copy(createdAt = Instant.fromEpochMilliseconds(0))
                ),
            )
        )
        assertEquals(original.intent, staged.intent)
    }

    @Test
    fun anotherAccountAudienceSpaceOrConversationCannotAdoptTheReceipt() {
        for (other in
            listOf(
                access.copy(accountId = id(8)),
                access.copy(spaceId = id(8)),
                access.copy(audience = "https://other.example.test/"),
            )) {
            assertFalse(validCollaborationSpaceReceipt(other, conversation, original))
            assertNull(collaborationSpaceReceiptState(other, staged, original))
        }
        assertFalse(validCollaborationSpaceReceipt(access, id(8), original))
    }

    @Test
    fun malformedIdentityCannotAdmitEvenAnEmptyLedger() {
        for (other in
            listOf(
                access.copy(sessionId = ""),
                access.copy(accountId = zeroId),
                access.copy(spaceId = zeroId),
            )) {
            assertFalse(validCollaborationSpaceReceiptLedger(other, conversation, emptyList()))
        }
        assertFalse(validCollaborationSpaceReceiptLedger(access, zeroId, emptyList()))
        assertTrue(validCollaborationSpaceReceiptLedger(access, conversation, emptyList()))
    }

    @Test
    fun newReceiptsRequireExactPhysicalCoordinatesAndValidOrigin() {
        for (changed in
            listOf(
                device.copy(nativeSessionId = zeroId),
                device.copy(deviceId = zeroId),
                device.copy(bootstrapOperationId = zeroId),
                device.copy(roomId = "invalid"),
            )) {
            assertFalse(
                validCollaborationSpaceReceipt(
                    access,
                    conversation,
                    original.copy(device = changed),
                )
            )
        }
        for (changed in
            listOf(origin.copy(sender = "invalid"), origin.copy(contentHash = "a".repeat(63)))) {
            assertFalse(
                validCollaborationSpaceReceipt(
                    access,
                    conversation,
                    original.copy(origin = changed),
                )
            )
        }
        assertFalse(
            validCollaborationSpaceReceipt(access, conversation, original.copy(transactionId = ""))
        )
    }

    @Test
    fun aliasesAndTheWholeJournalAreBoundedWithoutEvictingOriginals() {
        for (events in
            listOf(
                emptyList(),
                listOf("invalid"),
                original.eventIds + original.eventIds,
                (1..129).map { "\$event-$it" },
            )) {
            assertFalse(
                validCollaborationSpaceReceipt(
                    access,
                    conversation,
                    original.copy(eventIds = events),
                )
            )
        }
        assertTrue(
            validCollaborationSpaceReceiptLedger(access, conversation, (1..128).map(::receipt))
        )
        assertFalse(
            validCollaborationSpaceReceiptLedger(access, conversation, (1..129).map(::receipt))
        )
        val full =
            (1..4).map { index ->
                receipt(index).copy(eventIds = (1..128).map { "\$event-$index-$it" })
            }
        assertTrue(validCollaborationSpaceReceiptLedger(access, conversation, full))
        assertFalse(validCollaborationSpaceReceiptLedger(access, conversation, full + receipt(5)))
    }

    @Test
    fun oneProviderEventOrTransactionCannotConfirmTwoLogicalOriginals() {
        for (other in
            listOf(
                original,
                receipt(2).copy(eventIds = original.eventIds),
                receipt(2).copy(transactionId = original.transactionId),
                receipt(2)
                    .copy(
                        transactionId = original.transactionId,
                        device = device.copy(bootstrapOperationId = id(9)),
                    ),
            )) {
            assertFalse(
                validCollaborationSpaceReceiptLedger(access, conversation, listOf(original, other))
            )
        }
        val differentDevice =
            receipt(2)
                .copy(
                    transactionId = original.transactionId,
                    device = device.copy(deviceId = id(9)),
                )
        assertTrue(
            validCollaborationSpaceReceiptLedger(
                access,
                conversation,
                listOf(original, differentDevice),
            )
        )
    }

    @Test
    fun onlyExactStagedFactsBecomeSentAndRetainTheFirstEvent() {
        val aliases = original.copy(eventIds = original.eventIds + "\$alias")
        val expected =
            CollaborationMessageState.Sent(
                device,
                original.transactionId,
                original.eventIds.first(),
                origin,
            )
        assertEquals(expected, collaborationSpaceReceiptState(access, staged, aliases))
        for (changed in
            listOf(
                original.copy(transactionId = "other"),
                original.copy(device = device.copy(deviceId = id(9))),
                original.copy(origin = origin.copy(contentHash = "b".repeat(64))),
                original.copy(intent = original.intent.copy(text = "Changed")),
            )) {
            assertNull(collaborationSpaceReceiptState(access, staged, changed))
        }
    }

    @Test
    fun anExistingSentPrimaryIsNotReplacedByAReorderedAlias() {
        val sent =
            CollaborationMessageState.Sent(
                device,
                original.transactionId,
                original.eventIds.first(),
                origin,
            )
        val record = staged.copy(revision = 3, state = sent)
        assertEquals(
            sent,
            collaborationSpaceReceiptState(
                access,
                record,
                original.copy(eventIds = listOf("\$alias") + original.eventIds),
            ),
        )
        assertNull(
            collaborationSpaceReceiptState(
                access,
                record,
                original.copy(eventIds = listOf("\$alias")),
            )
        )
    }

    @Test
    fun queuedCancelledAndUnresolvedStagingNeverBecomeAnInferredAcknowledgement() {
        for (state in
            listOf(CollaborationMessageState.Queued, CollaborationMessageState.Cancelled)) {
            assertNull(
                collaborationSpaceReceiptState(
                    access,
                    staged.copy(revision = 0, state = state),
                    original,
                )
            )
        }
        assertNull(
            collaborationSpaceReceiptState(
                access,
                staged.copy(
                    revision = 1,
                    state = CollaborationMessageState.Staging(device, emptySet(), origin),
                ),
                original,
            )
        )
        assertNull(
            collaborationSpaceReceiptState(
                access,
                staged.copy(
                    state = CollaborationMessageState.Staged(device, original.transactionId)
                ),
                original,
            )
        )
    }

    @Test
    fun editAcknowledgementMustKeepTheOriginalReplyForwardingRoomAndSender() {
        val target =
            CollaborationEditTarget(
                device.roomId,
                "\$target",
                id(9),
                origin.sender,
                origin.contentHash,
                10,
                5,
                "\$reply",
                true,
            )
        val edited =
            original.copy(
                intent =
                    original.intent.copy(
                        replyToEventId = "\$reply",
                        forwarded = true,
                        editTarget = target,
                    )
            )
        assertTrue(validCollaborationSpaceReceipt(access, conversation, edited))
        for (changed in
            listOf(
                edited.copy(intent = edited.intent.copy(replyToEventId = null)),
                edited.copy(intent = edited.intent.copy(forwarded = false)),
                edited.copy(device = device.copy(roomId = "!other:matrix.example.test")),
                edited.copy(origin = origin.copy(sender = "@other:matrix.example.test")),
            )) {
            assertFalse(validCollaborationSpaceReceipt(access, conversation, changed))
        }
    }

    private fun receipt(index: Int) =
        CollaborationSpaceMessageReceipt(
            CollaborationSpaceMessageIntent(
                id(100 + index),
                access.messageOwner(),
                conversation,
                Instant.parse("2026-11-10T00:00:00Z"),
                " Original $index\n",
            ),
            device,
            "transaction-$index",
            origin,
            listOf("\$event-$index"),
        )

    private companion object {
        const val zeroId = "00000000-0000-0000-0000-000000000000"

        fun id(value: Int) = "a3000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
    }
}
