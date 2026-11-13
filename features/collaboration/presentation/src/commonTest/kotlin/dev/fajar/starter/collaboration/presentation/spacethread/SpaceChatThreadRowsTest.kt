package dev.fajar.starter.collaboration.presentation.spacethread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.thread.ChatMessageOwnership
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceChatThreadRowsTest {
    @Test
    fun aGestureRendersBeforeHistoryAndWithoutInventingACompanyCommand() {
        val f = SpaceChatRowsFixture()
        val row = f.rows(composer = f.composer(1)).single()
        assertEquals(SpaceChatMessageSource.GroupCommand(f.original().id), row.identity.source)
        assertEquals(f.access.messageOwner(), row.identity.owner)
        assertEquals("Original 1", row.bubble.text)
        assertEquals(ChatMessageOwnership.SELF, row.bubble.ownership)
        assertEquals(CollaborationMessageDelivery.PENDING, row.bubble.delivery)
        assertNull(row.bubble.localId)
        assertNull(row.bubble.reference)
    }

    @Test
    fun equalGroupAndOriginalUuidsRemainSeparateRowsAndActionIdentities() {
        val f = SpaceChatRowsFixture()
        val rows =
            f.rows(
                f.thread(
                    commands = listOf(f.record()),
                    originals = listOf(f.originalRecord(f.record())),
                    location = f.originalLocation,
                )
            )
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.identity }.toSet().size)
        assertEquals(2, rows.map { it.bubble.key }.toSet().size)
        val original =
            rows
                .map { it.identity.source }
                .filterIsInstance<SpaceChatMessageSource.OriginalCommand>()
                .single()
        assertEquals(f.company.messageOwner(), original.owner)
        assertEquals(f.originalLocation.conversationId, original.conversationId)
        assertNotEquals(f.conversationId, original.conversationId)
    }

    @Test
    fun originalHistoryConsumesOnlyItsExactOriginalCommandAndKeepsTheGroupGesture() {
        val f = SpaceChatRowsFixture()
        val rows =
            f.rows(
                f.thread(
                    history = listOf(f.history()),
                    originals = listOf(f.originalRecord()),
                    location = f.originalLocation,
                ),
                f.composer(1),
            )
        assertEquals(2, rows.size)
        val pending = rows.single { it.identity.source is SpaceChatMessageSource.GroupCommand }
        val sent = rows.single { it.identity.source is SpaceChatMessageSource.OriginalCommand }
        assertEquals(CollaborationMessageDelivery.PENDING, pending.bubble.delivery)
        assertEquals(CollaborationMessageDelivery.SENT, sent.bubble.delivery)
        assertEquals(f.history().primaryEventId, sent.bubble.eventId)
    }

    @Test
    fun anOriginalLedgerWithoutItsActualPhysicalHistoryLocationIsRejected() {
        val f = SpaceChatRowsFixture()
        assertIs<AppResult.Failed>(
            spaceChatThreadRows(
                f.access,
                f.conversationId,
                f.thread(originals = listOf(f.originalRecord())),
                SpaceChatComposerPresentation(),
            )
        )
    }

    @Test
    fun exactHistoryKeepsTheCommandIdentityAndDisplayedOriginalTimestamp() {
        val f = SpaceChatRowsFixture()
        val before = f.rows(composer = f.composer(1))
        val message = f.history().copy(timestamp = f.history().timestamp + 60_000)
        val after = f.rows(f.thread(listOf(message), listOf(f.sent())), previous = before)
        assertEquals(1, after.size)
        assertEquals(before.single().identity, after.single().identity)
        assertEquals(before.single().bubble.timestamp, after.single().bubble.timestamp)
        assertEquals(before.single().position, after.single().position)
        assertEquals(CollaborationMessageDelivery.SENT, after.single().bubble.delivery)
    }

    @Test
    fun equalIdsWithoutExactRoomAliasSenderHashAndTextCannotMerge() {
        val f = SpaceChatRowsFixture()
        val original = f.history()
        for (changed in
            listOf(
                original.copy(sender = "@other:example.test"),
                original.copy(logicalId = chatId(777)),
                original.copy(contentHash = "b".repeat(64)),
                original.copy(text = "Different original"),
                original.copy(
                    primaryEventId = "${'$'}different",
                    eventIds = listOf("${'$'}different"),
                ),
            )) {
            assertEquals(2, f.rows(f.thread(listOf(changed), listOf(f.sent()))).size)
        }
        val location =
            CollaborationSpaceHistoryLocation.Space(
                f.access,
                f.conversationId,
                "!other:example.test",
            )
        assertEquals(
            2,
            f.rows(f.thread(listOf(original), listOf(f.sent()), location = location)).size,
        )
    }

    @Test
    fun ambiguousEvidenceAcrossBothCommandNamespacesIsNotArbitrarilySelected() {
        val f = SpaceChatRowsFixture()
        assertIs<AppResult.Failed>(
            spaceChatThreadRows(
                f.access,
                f.conversationId,
                f.thread(
                    listOf(f.history()),
                    listOf(f.sent()),
                    listOf(f.originalRecord()),
                    f.originalLocation,
                ),
                SpaceChatComposerPresentation(),
            )
        )
    }

    @Test
    fun hiddenHistoryCannotReappearThroughTheSeparatelyObservedCommand() {
        val f = SpaceChatRowsFixture()
        val rows =
            f.rows(f.thread(listOf(f.history().copy(locallyHidden = true)), listOf(f.sent())))
        assertTrue(rows.isEmpty())
    }

    @Test
    fun redactionRetainsTheIdentityButRemovesTextReplyAndForwardedPresentation() {
        val f = SpaceChatRowsFixture()
        val initial = f.rows(f.thread(listOf(f.history()), listOf(f.sent())))
        val redacted =
            f.history()
                .copy(
                    redacted = true,
                    text = null,
                    forwarded = true,
                    replyToEventId = "${'$'}quoted",
                )
        val row = f.rows(f.thread(listOf(redacted), listOf(f.sent())), previous = initial).single()
        assertEquals(initial.single().identity, row.identity)
        assertEquals(CollaborationMessageKind.REDACTED, row.bubble.kind)
        assertNull(row.bubble.text)
        assertNull(row.bubble.replyToEventId)
        assertFalse(row.bubble.forwarded)
    }

    @Test
    fun confirmedCancellationDoesNotResurrectFromAStaleQueuedInventory() {
        val f = SpaceChatRowsFixture()
        val composer =
            SpaceChatComposerPresentation(
                represented =
                    listOf(
                        SpaceChatPendingSubmission(
                            f.original(),
                            true,
                            null,
                            f.record().copy(state = CollaborationMessageState.Cancelled),
                        )
                    )
            )
        assertTrue(f.rows(f.thread(commands = listOf(f.record())), composer).isEmpty())
    }

    @Test
    fun knownSelfIdentitiesDetermineBubbleSideWithoutGuessingFromLogicalIds() {
        val f = SpaceChatRowsFixture()
        val olderSelf = "@old:example.test"
        val rows =
            f.rows(
                f.thread(
                    history =
                        listOf(
                            f.history(),
                            f.history(f.sent(2)).copy(sender = olderSelf),
                            f.history(f.sent(3)).copy(sender = "@peer:example.test"),
                        ),
                    location = f.originalLocation,
                    selected = AppResult.Success(f.selected()),
                    original = AppResult.Success(CollaborationSelfIdentity(olderSelf)),
                )
            )
        assertEquals(2, rows.count { it.bubble.ownership == ChatMessageOwnership.SELF })
        assertEquals(1, rows.count { it.bubble.ownership == ChatMessageOwnership.COLLEAGUE })
        assertTrue(rows.all { it.identity.source is SpaceChatMessageSource.History })
    }

    @Test
    fun missingIdentityKeepsReadableHistoryWithExplicitUnknownOwnership() {
        val f = SpaceChatRowsFixture()
        val row =
            f.rows(f.thread(listOf(f.history()), selected = AppResult.Failed(f.base.storage)))
                .single()
        assertEquals("Original 1", row.bubble.text)
        assertEquals(ChatMessageOwnership.UNKNOWN, row.bubble.ownership)
    }

    @Test
    fun heldAndUncertainGesturesRetainExactTextAndDistinctFailureIcons() {
        val f = SpaceChatRowsFixture()
        val first = SpaceChatPendingSubmission(f.original(1), true, null, reviewRequired = true)
        val second = SpaceChatPendingSubmission(f.original(2), false, f.base.storage)
        val third = SpaceChatPendingSubmission(f.original(3), true, null)
        val rows = f.rows(composer = SpaceChatComposerPresentation(listOf(first, second, third)))
        assertEquals(listOf("Original 3", "Original 2", "Original 1"), rows.map { it.bubble.text })
        assertEquals(
            listOf(
                CollaborationMessageDelivery.PENDING,
                CollaborationMessageDelivery.FAILED,
                CollaborationMessageDelivery.FAILED,
            ),
            rows.map { it.bubble.delivery },
        )
    }

    @Test
    fun previousRowsFromAnotherAccountCannotEnterTheReplacementProjection() {
        val f = SpaceChatRowsFixture()
        val previous = f.rows(composer = f.composer(1))
        assertIs<AppResult.Failed>(
            spaceChatThreadRows(
                f.access.copy(accountId = chatId(9999)),
                f.conversationId,
                null,
                SpaceChatComposerPresentation(),
                previous,
            )
        )
    }

    @Test
    fun duplicateHistoryAndOversizedGestureInventoriesFailWithoutTruncation() {
        val f = SpaceChatRowsFixture()
        assertIs<AppResult.Failed>(
            spaceChatThreadRows(
                f.access,
                f.conversationId,
                f.thread(listOf(f.history(), f.history())),
                SpaceChatComposerPresentation(),
            )
        )
        val oversized =
            SpaceChatComposerPresentation(
                pending = List(129) { SpaceChatPendingSubmission(f.original(it + 1), false, null) }
            )
        assertIs<AppResult.Failed>(spaceChatThreadRows(f.access, f.conversationId, null, oversized))
    }
}
