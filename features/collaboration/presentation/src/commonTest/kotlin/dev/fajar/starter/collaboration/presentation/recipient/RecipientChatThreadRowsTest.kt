package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatMessageSource
import dev.fajar.starter.collaboration.presentation.thread.ChatMessageOwnership
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

class RecipientChatThreadRowsTest {
    @Test
    fun capturedSendRendersBeforeAnyCanonicalTargetWithoutInventingActionCoordinates() {
        val f = RecipientChatRowsFixture()
        val row = f.rows(conversation = null).single()
        assertEquals("Recipient 1", row.bubble.text)
        assertEquals(CollaborationMessageDelivery.PENDING, row.bubble.delivery)
        assertEquals(ChatMessageOwnership.SELF, row.bubble.ownership)
        assertEquals(f.peer, row.identity.peerAccountId)
        assertNull(row.canonicalIdentity)
        assertNull(row.bubble.localId)
        assertNull(row.bubble.reference)
    }

    @Test
    fun realAssociationAndExactHistoryKeepTheOriginalKeyPositionAndTimestamp() {
        val f = RecipientChatRowsFixture()
        val before = f.rows(conversation = null)
        val associated = f.rows(previous = before)
        val sent = f.facts.sent()
        val status = f.rows(f.presentation(records = listOf(sent)), previous = associated)
        val history =
            f.facts.rows.thread(
                history =
                    listOf(
                        f.facts.rows
                            .history(sent)
                            .copy(timestamp = sent.intent.createdAt.toEpochMilliseconds() + 60_000)
                    )
            )
        val after =
            f.rows(
                f.presentation(records = listOf(sent), thread = history),
                history,
                previous = status,
            )
        for (rows in listOf(associated, status, after)) {
            assertEquals(1, rows.size)
            assertEquals(before.single().identity, rows.single().identity)
            assertEquals(before.single().bubble.key, rows.single().bubble.key)
            assertEquals(before.single().position, rows.single().position)
            assertEquals(before.single().bubble.timestamp, rows.single().bubble.timestamp)
            assertEquals(f.conversation.id, rows.single().canonicalIdentity?.conversationId)
            assertNotEquals(f.peer, rows.single().canonicalIdentity?.conversationId)
        }
        assertEquals(CollaborationMessageDelivery.SENT, after.single().bubble.delivery)
    }

    @Test
    fun laterClockSkewedSendStaysAtTheEndAcrossOutOfOrderReceipts() {
        val f = RecipientChatRowsFixture()
        val first = f.facts.original(1)
        val second =
            f.facts.original(2).let {
                it.copy(
                    original = it.original.copy(savedAt = it.original.savedAt - 5.minutes),
                    submittedAt = it.submittedAt - 5.minutes,
                )
            }
        val before = f.rows(f.presentation(listOf(first)), conversation = null)
        val typed =
            f.rows(f.presentation(listOf(first, second)), conversation = null, previous = before)
        assertEquals(listOf("Recipient 2", "Recipient 1"), typed.map { it.bubble.text })
        val oneSent =
            f.rows(
                f.presentation(listOf(first, second), listOf(f.facts.sent(first))),
                previous = typed,
            )
        val allSent =
            f.rows(
                f.presentation(
                    listOf(first, second),
                    listOf(f.facts.sent(first), f.facts.sent(second)),
                ),
                previous = oneSent,
            )
        assertEquals(
            typed.map { it.identity to it.position },
            allSent.map { it.identity to it.position },
        )
        assertEquals(typed.map { it.bubble.timestamp }, allSent.map { it.bubble.timestamp })
    }

    @Test
    fun sourceRetirementAndStaleQueuedPublicationDoNotRemoveOrDowngradeTheBubble() {
        val f = RecipientChatRowsFixture()
        val sent = f.facts.sent()
        val composer = f.presentation(records = listOf(sent))
        val before = f.rows(composer)
        val retained = f.facts.success(f.facts.read(previous = composer))
        val after =
            f.rows(
                retained,
                f.facts.rows.thread(commands = listOf(f.facts.record())),
                previous = before,
            )
        assertEquals(before, after)
    }

    @Test
    fun companyCommandWithTheSameUuidKeepsItsPhysicalIdentityAndCannotConsumeRecipientGesture() {
        val f = RecipientChatRowsFixture()
        val original = f.facts.rows.originalRecord(f.facts.sent())
        val thread =
            f.facts.rows.thread(
                history = listOf(f.facts.rows.history(f.facts.sent())),
                originals = listOf(original),
                location = f.facts.rows.originalLocation,
            )
        val rows = f.rows(thread = thread)
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.bubble.key }.toSet().size)
        val pending = rows.single { it.identity.source is SpaceChatMessageSource.GroupCommand }
        val company = rows.single { it.identity.source is SpaceChatMessageSource.OriginalCommand }
        assertEquals(CollaborationMessageDelivery.PENDING, pending.bubble.delivery)
        assertEquals(CollaborationMessageDelivery.SENT, company.bubble.delivery)
        val source = assertIs<SpaceChatMessageSource.OriginalCommand>(company.identity.source)
        assertEquals(f.facts.rows.company.messageOwner(), source.owner)
        assertEquals(f.facts.rows.originalLocation.conversationId, source.conversationId)
    }

    @Test
    fun equalUuidWithoutExactProviderEvidenceRemainsTwoRows() {
        val f = RecipientChatRowsFixture()
        val sent = f.facts.sent()
        val message = f.facts.rows.history(sent)
        for (other in
            listOf(
                message.copy(sender = "@other:example.test"),
                message.copy(contentHash = "b".repeat(64)),
                message.copy(text = "Other text"),
                message.copy(logicalId = chatId(505)),
                message.copy(eventIds = listOf("${'$'}other"), primaryEventId = "${'$'}other"),
            )) {
            val thread = f.facts.rows.thread(history = listOf(other))
            val rows = f.rows(f.presentation(records = listOf(sent), thread = thread), thread)
            assertEquals(2, rows.size)
            assertEquals(2, rows.map { it.identity }.toSet().size)
        }
    }

    @Test
    fun hiddenHistoryAndStaleQueuedInventoryDoNotResurrectTheOriginal() {
        val f = RecipientChatRowsFixture()
        val sent = f.facts.sent()
        val thread =
            f.facts.rows.thread(
                history = listOf(f.facts.rows.history(sent).copy(locallyHidden = true)),
                commands = listOf(f.facts.record()),
            )
        val composer = f.presentation(records = listOf(sent), thread = thread)
        assertTrue(f.rows(composer, thread).isEmpty())
        assertTrue(
            f.rows(composer, f.facts.rows.thread(commands = listOf(f.facts.record()))).isEmpty()
        )
    }

    @Test
    fun redactionPreservesBubbleIdentityAndRemovesTextAndQuote() {
        val f = RecipientChatRowsFixture()
        val sent = f.facts.sent()
        val before = f.rows(f.presentation(records = listOf(sent)))
        val message =
            f.facts.rows
                .history(sent)
                .copy(
                    redacted = true,
                    text = null,
                    replyToEventId = "${'$'}quoted",
                    forwarded = true,
                )
        val thread = f.facts.rows.thread(history = listOf(message))
        val after =
            f.rows(
                    f.presentation(records = listOf(sent), thread = thread),
                    thread,
                    previous = before,
                )
                .single()
        assertEquals(before.single().identity, after.identity)
        assertEquals(CollaborationMessageKind.REDACTED, after.bubble.kind)
        assertNull(after.bubble.text)
        assertNull(after.bubble.replyToEventId)
        assertFalse(after.bubble.forwarded)
    }

    @Test
    fun confirmedCancellationCannotReappearFromAnOlderInventory() {
        val f = RecipientChatRowsFixture()
        val cancelled = f.facts.record().copy(state = CollaborationMessageState.Cancelled)
        val composer = f.presentation(records = listOf(cancelled))
        assertTrue(
            f.rows(composer, f.facts.rows.thread(commands = listOf(f.facts.record()))).isEmpty()
        )
    }

    @Test
    fun previousRowsCannotCrossPeerOwnerCanonicalTargetOrUnknownAssociation() {
        val f = RecipientChatRowsFixture()
        val previous = f.rows()
        val row = previous.single()
        for (changed in
            listOf(
                row.copy(identity = row.identity.copy(peerAccountId = chatId(506))),
                row.copy(
                    identity =
                        row.identity.copy(owner = row.identity.owner.copy(accountId = f.peer))
                ),
                row.copy(
                    canonicalIdentity = row.canonicalIdentity?.copy(conversationId = chatId(507))
                ),
                row.copy(bubble = row.bubble.copy(key = "foreign")),
            )) {
            assertIs<AppResult.Failed>(f.read(previous = listOf(changed)))
        }
        assertIs<AppResult.Failed>(f.read(conversation = null, previous = previous))
        val foreign =
            f.facts.rows.thread().let {
                it.copy(history = it.history.copy(access = f.access.copy(accountId = f.peer)))
            }
        assertIs<AppResult.Failed>(f.read(thread = foreign))
    }

    @Test
    fun sameUuidWithMissingRecipientProvenanceDoesNotBecomeOurCommand() {
        val f = RecipientChatRowsFixture()
        val malformed =
            f.facts.record().let { it.copy(intent = it.intent.copy(recipientOrigin = null)) }
        assertIs<AppResult.Failed>(
            f.read(thread = f.facts.rows.thread(commands = listOf(malformed)))
        )
        val wrongPeer =
            f.facts.record(f.facts.original(2)).let {
                it.copy(
                    intent =
                        it.intent.copy(
                            recipientOrigin =
                                it.intent.recipientOrigin?.copy(peerAccountId = chatId(508))
                        )
                )
            }
        assertIs<AppResult.Failed>(
            f.read(thread = f.facts.rows.thread(commands = listOf(wrongPeer)))
        )
    }

    @Test
    fun oversizedSourcesAndHistoryFailInsteadOfSilentlyDroppingRows() {
        val f = RecipientChatRowsFixture()
        val originals = (1..128).map { RecipientChatPendingSubmission(f.facts.original(it), true) }
        assertEquals(
            128,
            f.rows(RecipientChatPresentation(pending = originals), conversation = null).size,
        )
        assertIs<AppResult.Failed>(
            f.read(
                RecipientChatPresentation(
                    pending = originals + RecipientChatPendingSubmission(f.facts.original(129))
                ),
                conversation = null,
            )
        )
        val history = (1..501).map { f.facts.rows.history(f.facts.sent(f.facts.original(it))) }
        assertIs<AppResult.Failed>(
            f.read(RecipientChatPresentation(), f.facts.rows.thread(history = history))
        )
    }

    @Test
    fun ambiguousProviderEvidenceBetweenPhysicalCommandOwnersIsRejected() {
        val f = RecipientChatRowsFixture()
        val sent = f.facts.sent()
        val thread =
            f.facts.rows.thread(
                history = listOf(f.facts.rows.history(sent)),
                originals = listOf(f.facts.rows.originalRecord(sent)),
                location = f.facts.rows.originalLocation,
            )
        assertIs<AppResult.Failed>(
            f.read(f.presentation(records = listOf(sent), thread = thread), thread)
        )
    }
}
