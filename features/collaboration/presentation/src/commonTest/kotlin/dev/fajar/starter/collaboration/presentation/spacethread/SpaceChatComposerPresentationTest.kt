package dev.fajar.starter.collaboration.presentation.spacethread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceComposerIntent
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class SpaceChatComposerPresentationTest {
    @Test
    fun aNewGestureAppearsBeforeAnyRecordOrHistoryResult() {
        val f = Fixture()
        val state = f.project(provisional = listOf(f.pending))
        assertEquals(listOf(f.pending), state.pending)
        assertTrue(state.represented.isEmpty())
    }

    @Test
    fun aColdOutboxOriginalSurvivesOutboxRemovalAndAnAbsentRecord() {
        val f = Fixture()
        val initial =
            f.project(outbox = listOf(CollaborationComposerOutboxEntry(f.original, false)))
        val next =
            f.project(
                previous = initial,
                progress = listOf(CollaborationSpaceComposerMessageProgress(f.original, null)),
            )
        assertEquals(initial, next)
        assertTrue(next.pending.single().durable)
        assertNull(next.pending.single().record)
    }

    @Test
    fun aStaleActiveInventoryAndLaterAbsenceCannotReplaceAnExactSentRecord() {
        val f = Fixture()
        val sent = f.sent()
        val initial =
            f.project(provisional = listOf(f.pending), progress = listOf(f.progress(sent)))
        val next = f.project(previous = initial, thread = f.thread(emptyList(), listOf(f.record)))
        assertEquals(sent, next.pending.single().record)
        assertEquals(next, f.project(previous = next))
        assertTrue(next.represented.isEmpty())
    }

    @Test
    fun aMatchingUuidWithoutTheExactProviderEventAndOriginDoesNotRetireTheOriginal() {
        val f = Fixture()
        val sent = f.sent()
        val message = f.history(sent)
        for (changed in
            listOf(
                message.copy(
                    eventIds = listOf("${'$'}different"),
                    primaryEventId = "${'$'}different",
                ),
                message.copy(sender = "@another:example.test"),
                message.copy(contentHash = "b".repeat(64)),
                message.copy(text = "Different text"),
                message.copy(logicalId = chatId(998)),
            )) {
            val value =
                f.project(
                    provisional = listOf(f.pending),
                    progress = listOf(f.progress(sent)),
                    thread = f.thread(listOf(changed)),
                )
            assertEquals(listOf(f.original), value.pending.map { it.original })
            assertTrue(value.represented.isEmpty())
        }
        val wrongRoom =
            f.thread(listOf(message)).let {
                it.copy(
                    history =
                        it.history.copy(
                            location =
                                CollaborationSpaceHistoryLocation.Space(
                                    f.base.access,
                                    f.base.conversationId,
                                    "!other:example.test",
                                )
                        )
                )
            }
        assertEquals(
            1,
            f.project(
                    provisional = listOf(f.pending),
                    progress = listOf(f.progress(sent)),
                    thread = wrongRoom,
                )
                .pending
                .size,
        )
    }

    @Test
    fun actualSentHistoryRetainsVisualIdentityAndSuppressesAStaleOutboxEntry() {
        val f = Fixture()
        val sent = f.sent()
        val thread = f.thread(listOf(f.history(sent)))
        val shown =
            f.project(
                provisional = listOf(f.pending),
                progress = listOf(f.progress(sent)),
                thread = thread,
            )
        assertTrue(shown.pending.isEmpty())
        assertEquals(sent, shown.represented.single().record)
        val next =
            f.project(
                previous = shown,
                outbox = listOf(CollaborationComposerOutboxEntry(f.original, false)),
                thread = thread,
            )
        assertEquals(shown, next)
    }

    @Test
    fun confirmedCancellationWinsOverItsOldQueuedInventory() {
        val f = Fixture()
        val cancelled = f.record.copy(state = CollaborationMessageState.Cancelled)
        val shown =
            f.project(
                provisional = listOf(f.pending),
                progress = listOf(f.progress(cancelled)),
                thread = f.thread(emptyList(), listOf(f.record)),
            )
        assertTrue(shown.pending.isEmpty())
        assertEquals(cancelled, shown.represented.single().record)
        assertEquals(
            shown,
            f.project(
                previous = shown,
                outbox = listOf(CollaborationComposerOutboxEntry(f.original, false)),
                thread = f.thread(emptyList(), listOf(f.record)),
            ),
        )
    }

    @Test
    fun theSameIdWithDifferentImmutableContentCannotReplaceTheCapturedGesture() {
        val f = Fixture()
        val changed = f.original.copy(original = f.original.original.copy(text = "Changed"))
        val originalState = f.project(provisional = listOf(f.pending))
        assertIs<AppResult.Failed>(
            projectSpaceChatComposer(
                f.base.access,
                f.base.conversationId,
                originalState,
                listOf(CollaborationComposerOutboxEntry(changed, false)),
                emptyList(),
                emptyList(),
                null,
            )
        )
        assertEquals(f.original, originalState.pending.single().original)
    }

    @Test
    fun anOriginalCompanyCommandWithTheSameIdCannotOwnAGroupGesture() {
        val f = Fixture()
        val company =
            CollaborationMessageRecord(
                CollaborationMessageIntent(
                    f.original.id,
                    CollaborationMessageOwner(
                        f.base.access.audience,
                        f.base.access.accountId,
                        chatId(996),
                    ),
                    f.base.conversationId,
                    f.original.submittedAt,
                    f.original.original.text,
                ),
                0,
                CollaborationMessageState.Queued,
            )
        val thread = f.thread(emptyList()).copy(originalCommands = listOf(company))
        val shown = f.project(provisional = listOf(f.pending), thread = thread)
        assertEquals(listOf(f.pending), shown.pending)
        assertNull(shown.pending.single().record)
    }

    @Test
    fun confirmationOfAPrefixKeepsTheOriginalGestureOrder() {
        val f = Fixture()
        val originals = (1..4).map { f.original(it) }
        val captured = originals.map { SpaceChatPendingSubmission(it, false, null) }
        val pending = f.project(provisional = captured)
        val firstSent = f.sent(originals.first())
        val confirmed =
            f.project(
                previous = pending,
                progress =
                    listOf(CollaborationSpaceComposerMessageProgress(originals.first(), firstSent)),
            )
        assertEquals(originals, confirmed.pending.map { it.original })
        assertEquals(
            originals.map { it.submittedAt },
            confirmed.pending.map { it.original.submittedAt },
        )
    }

    @Test
    fun heldOutboxAndActualPersistenceFailureRemainAttachedToTheirOwnOriginal() {
        val f = Fixture()
        val failed = f.pending.copy(failure = f.base.storage)
        val shown =
            f.project(
                outbox = listOf(CollaborationComposerOutboxEntry(f.original, true)),
                provisional = listOf(failed),
            )
        assertTrue(shown.pending.single().reviewRequired)
        assertEquals(f.base.storage, shown.pending.single().failure)
        val retry = f.project(previous = shown, provisional = listOf(f.pending))
        assertNull(retry.pending.single().failure)
        assertTrue(retry.pending.single().reviewRequired)
    }

    @Test
    fun recentTerminalIdentitiesAreBoundedWithoutDroppingUnknownPendingOriginals() {
        val f = Fixture()
        val represented =
            (1..128).map { original ->
                val value = f.original(original)
                SpaceChatPendingSubmission(
                    value,
                    true,
                    null,
                    f.record(value).copy(state = CollaborationMessageState.Cancelled),
                )
            }
        val last = f.original(129)
        val pending = f.original(130)
        val shown =
            f.project(
                previous = SpaceChatComposerPresentation(represented = represented),
                provisional =
                    listOf(
                        SpaceChatPendingSubmission(last, true, null),
                        SpaceChatPendingSubmission(pending, false, null),
                    ),
                progress =
                    listOf(
                        CollaborationSpaceComposerMessageProgress(
                            last,
                            f.record(last).copy(state = CollaborationMessageState.Cancelled),
                        )
                    ),
            )
        assertEquals(128, shown.represented.size)
        assertEquals(last, shown.represented.first().original)
        assertEquals(listOf(pending), shown.pending.map { it.original })
    }

    @Test
    fun duplicateOrOversizedInputsFailWithoutSilentlyDroppingOriginals() {
        val f = Fixture()
        for (values in
            listOf(
                listOf(f.pending, f.pending),
                List(129) { SpaceChatPendingSubmission(f.original(it + 1), false, null) },
            )) {
            assertIs<AppResult.Failed>(
                projectSpaceChatComposer(
                    f.base.access,
                    f.base.conversationId,
                    SpaceChatComposerPresentation(),
                    emptyList(),
                    values,
                    emptyList(),
                    null,
                )
            )
        }
    }

    private class Fixture {
        val base = SpaceChatThreadFixture()
        val original = original(1)
        val pending = SpaceChatPendingSubmission(original, false, null)
        val record = record(original)

        fun original(index: Int) =
            CollaborationComposerSubmission(
                CollaborationDraft(
                    base.conversationId,
                    chatId(900 + index),
                    "Original $index",
                    base.base.now + index.milliseconds,
                ),
                chatId(2000 + index),
                base.base.now + index.milliseconds,
            )

        fun record(original: CollaborationComposerSubmission) =
            CollaborationSpaceMessageRecord(
                collaborationSpaceComposerIntent(base.access, original),
                0,
                CollaborationMessageState.Queued,
            )

        fun sent(original: CollaborationComposerSubmission = this.original) =
            record(original)
                .copy(
                    revision = 3,
                    state =
                        CollaborationMessageState.Sent(
                            CollaborationMessageDevice(
                                chatId(991),
                                chatId(992),
                                chatId(993),
                                "!room:example.test",
                            ),
                            "original-transaction",
                            "${'$'}event",
                            CollaborationMessageOrigin("@fictional:example.test", "a".repeat(64)),
                        ),
                )

        fun progress(record: CollaborationSpaceMessageRecord) =
            CollaborationSpaceComposerMessageProgress(original, record)

        fun history(record: CollaborationSpaceMessageRecord): CollaborationHistoryMessage {
            val sent = record.state as CollaborationMessageState.Sent
            return CollaborationHistoryMessage(
                requireNotNull(sent.origin).sender,
                record.intent.id,
                sent.eventId,
                record.intent.createdAt.toEpochMilliseconds(),
                sent.origin!!.contentHash,
                record.intent.text,
                listOf(sent.eventId),
                false,
            )
        }

        fun thread(
            messages: List<CollaborationHistoryMessage>,
            commands: List<CollaborationSpaceMessageRecord> = emptyList(),
        ) =
            CollaborationSpaceThreadSnapshot.Bound(
                CollaborationSpaceThreadHistory(
                    base.access,
                    base.conversationId,
                    CollaborationSpaceHistoryLocation.Space(
                        base.access,
                        base.conversationId,
                        "!room:example.test",
                    ),
                    CollaborationHistoryPage(messages, null, roomId = "!room:example.test"),
                ),
                commands,
                emptyList(),
                base.metadata,
                CollaborationSpaceThreadParticipants(
                    AppResult.Success(null),
                    AppResult.Success(null),
                    AppResult.Success(null),
                ),
            )

        fun project(
            previous: SpaceChatComposerPresentation = SpaceChatComposerPresentation(),
            outbox: List<CollaborationComposerOutboxEntry> = emptyList(),
            provisional: List<SpaceChatPendingSubmission> = emptyList(),
            progress: List<CollaborationSpaceComposerMessageProgress> = emptyList(),
            thread: CollaborationSpaceThreadSnapshot? = null,
        ) =
            assertIs<AppResult.Success<SpaceChatComposerPresentation>>(
                    projectSpaceChatComposer(
                        base.access,
                        base.conversationId,
                        previous,
                        outbox,
                        provisional,
                        progress,
                        thread,
                    )
                )
                .value
    }
}
