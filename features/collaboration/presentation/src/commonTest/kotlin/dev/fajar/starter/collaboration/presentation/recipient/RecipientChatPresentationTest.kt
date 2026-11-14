package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class RecipientChatPresentationTest {
    @Test
    fun provisionalMessageAppearsBeforeCanonicalMetadataOrStorage() {
        val f = RecipientChatPresentationFixture()
        val value = f.success(f.read(provisional = listOf(f.pending), conversation = null))
        assertEquals(listOf(RecipientChatPendingSubmission(f.first)), value.pending)
        assertTrue(value.represented.isEmpty())
    }

    @Test
    fun coldSourceOriginalSurvivesRetirementBeforeItsRecordArrives() {
        val f = RecipientChatPresentationFixture()
        val first = f.success(f.read(source = listOf(f.first)))
        val second =
            f.success(
                f.read(
                    previous = first,
                    progress = listOf(CollaborationSpaceRecipientMessageProgress(f.first, null)),
                )
            )
        assertEquals(first, second)
        assertTrue(second.pending.single().durable)
        assertNull(second.pending.single().record)
    }

    @Test
    fun staleQueuedInventoryAndAbsenceCannotReplaceTheExactSentRecord() {
        val f = RecipientChatPresentationFixture()
        val sent = f.sent()
        val first =
            f.success(
                f.read(
                    source = listOf(f.first),
                    progress = listOf(CollaborationSpaceRecipientMessageProgress(f.first, sent)),
                )
            )
        val second =
            f.success(
                f.read(previous = first, thread = f.rows.thread(commands = listOf(f.record())))
            )
        assertEquals(sent, second.pending.single().record)
        assertEquals(second, f.success(f.read(previous = second)))
        assertTrue(second.represented.isEmpty())
    }

    @Test
    fun exactHistoryRetiresTheDisplayOriginalAndStaleSourceCannotResurrectIt() {
        val f = RecipientChatPresentationFixture()
        val sent = f.sent()
        val value =
            f.success(
                f.read(
                    source = listOf(f.first),
                    progress = listOf(CollaborationSpaceRecipientMessageProgress(f.first, sent)),
                    thread = f.rows.thread(history = listOf(f.rows.history(sent))),
                )
            )
        assertTrue(value.pending.isEmpty())
        assertEquals(listOf(f.first), value.represented.map { it.original })
        val repeated =
            f.success(
                f.read(
                    previous = value,
                    source = listOf(f.first),
                    thread = f.rows.thread(commands = listOf(f.record())),
                )
            )
        assertTrue(repeated.pending.isEmpty())
        assertEquals(sent, repeated.represented.single().record)
    }

    @Test
    fun matchingUuidWithoutFullProviderEvidenceDoesNotRetireTheDisplayOriginal() {
        val f = RecipientChatPresentationFixture()
        val sent = f.sent()
        val message = f.rows.history(sent)
        for (different in
            listOf(
                message.copy(eventIds = listOf("${'$'}other"), primaryEventId = "${'$'}other"),
                message.copy(sender = "@different:example.test"),
                message.copy(contentHash = "b".repeat(64)),
                message.copy(text = "Different text"),
                message.copy(logicalId = chatId(500)),
            )) {
            val result =
                f.success(
                    f.read(
                        source = listOf(f.first),
                        progress =
                            listOf(CollaborationSpaceRecipientMessageProgress(f.first, sent)),
                        thread = f.rows.thread(history = listOf(different)),
                    )
                )
            assertEquals(listOf(f.first), result.pending.map { it.original })
            assertTrue(result.represented.isEmpty())
        }
    }

    @Test
    fun oldCanonicalIntentWithTheSameUuidNeverProvesRecipientDelivery() {
        val f = RecipientChatPresentationFixture()
        val record = f.record()
        val origin = requireNotNull(record.intent.recipientOrigin)
        for (intent in
            listOf(
                record.intent.copy(recipientOrigin = null),
                record.intent.copy(recipientOrigin = origin.copy(nextRevision = chatId(501))),
                record.intent.copy(
                    recipientOrigin =
                        origin.copy(draftSavedAt = origin.draftSavedAt - 1.milliseconds)
                ),
                record.intent.copy(recipientOrigin = origin.copy(peerAccountId = chatId(502))),
                record.intent.copy(conversationId = chatId(503)),
            )) {
            assertIs<AppResult.Failed>(
                f.read(
                    source = listOf(f.first),
                    progress =
                        listOf(
                            CollaborationSpaceRecipientMessageProgress(
                                f.first,
                                record.copy(intent = intent),
                            )
                        ),
                )
            )
        }
    }

    @Test
    fun confirmedCancellationSurvivesAnOlderQueuedPublication() {
        val f = RecipientChatPresentationFixture()
        val cancelled = f.record().copy(state = CollaborationMessageState.Cancelled)
        val first =
            f.success(
                f.read(
                    source = listOf(f.first),
                    progress =
                        listOf(CollaborationSpaceRecipientMessageProgress(f.first, cancelled)),
                )
            )
        val second =
            f.success(
                f.read(
                    previous = first,
                    source = listOf(f.first),
                    thread = f.rows.thread(commands = listOf(f.record())),
                )
            )
        assertTrue(second.pending.isEmpty())
        assertEquals(cancelled, second.represented.single().record)
    }

    @Test
    fun everyRetainedRepresentedRecordStillRequiresExactRecipientProof() {
        val f = RecipientChatPresentationFixture()
        val malformed = f.record().copy(intent = f.record().intent.copy(recipientOrigin = null))
        val previous =
            RecipientChatPresentation(
                represented =
                    listOf(RecipientChatPendingSubmission(f.first, true, record = malformed))
            )
        assertIs<AppResult.Failed>(f.read(previous = previous))
        val wrongTarget =
            previous.copy(
                represented =
                    listOf(
                        RecipientChatPendingSubmission(
                            f.first,
                            true,
                            record =
                                f.record()
                                    .copy(
                                        intent =
                                            f.record().intent.copy(conversationId = chatId(504))
                                    ),
                        )
                    )
            )
        assertIs<AppResult.Failed>(f.read(previous = wrongTarget))
    }

    @Test
    fun foreignHistoryOwnershipCannotRepresentAnOwnedRecipientOriginal() {
        val f = RecipientChatPresentationFixture()
        val sent = f.sent()
        val thread = f.rows.thread(history = listOf(f.rows.history(sent)))
        for (foreign in
            listOf(
                thread.copy(
                    history = thread.history.copy(access = f.access.copy(accountId = f.peer))
                ),
                thread.copy(history = thread.history.copy(conversationId = chatId(505))),
                thread.copy(
                    history =
                        thread.history.copy(
                            location =
                                CollaborationSpaceHistoryLocation.Space(
                                    f.access.copy(spaceId = chatId(506)),
                                    f.conversation.id,
                                    f.rows.room,
                                )
                        )
                ),
                thread.copy(
                    history =
                        thread.history.copy(
                            location =
                                CollaborationSpaceHistoryLocation.Original(
                                    f.rows.company.copy(accountId = f.peer),
                                    f.rows.originalLocation.conversationId,
                                    f.rows.room,
                                )
                        )
                ),
            )) {
            assertIs<AppResult.Failed>(
                f.read(
                    source = listOf(f.first),
                    progress = listOf(CollaborationSpaceRecipientMessageProgress(f.first, sent)),
                    thread = foreign,
                )
            )
        }
    }

    @Test
    fun duplicateOrChangedOriginalsCannotReplaceAnEarlierGesture() {
        val f = RecipientChatPresentationFixture()
        assertIs<AppResult.Failed>(f.read(source = listOf(f.first, f.first)))
        val first = f.success(f.read(source = listOf(f.first)))
        assertIs<AppResult.Failed>(
            f.read(
                previous = first,
                provisional =
                    listOf(
                        f.pending.copy(
                            original =
                                f.first.copy(original = f.first.original.copy(text = "Changed"))
                        )
                    ),
            )
        )
        assertIs<AppResult.Failed>(
            f.read(
                source =
                    listOf(
                        f.first.copy(
                            original = f.first.original.copy(peerAccountId = f.access.accountId)
                        )
                    )
            )
        )
    }

    @Test
    fun completeQuotaIsRetainedButOverflowFailsWithoutTruncatingPendingRows() {
        val f = RecipientChatPresentationFixture()
        val full = List(128) { f.original(it + 1) }
        val value = f.success(f.read(source = full))
        assertEquals(full, value.pending.map { it.original })
        assertIs<AppResult.Failed>(f.read(source = List(129) { f.original(it + 1) }))
        assertIs<AppResult.Failed>(
            f.read(
                previous = value,
                provisional = listOf(RecipientComposerPendingSubmission(f.original(129))),
            )
        )
    }

    @Test
    fun rapidOriginalsKeepSubmissionOrderWhenReceiptsArriveInAnotherOrder() {
        val f = RecipientChatPresentationFixture()
        val originals = (1..4).map(f::original)
        val first = f.success(f.read(source = originals.reversed()))
        assertEquals(originals, first.pending.map { it.original })
        val progress =
            listOf(originals[2], originals[0]).map {
                CollaborationSpaceRecipientMessageProgress(it, f.record(it))
            }
        val second = f.success(f.read(previous = first, progress = progress))
        assertEquals(originals, second.pending.map { it.original })
        assertEquals(
            originals.map { it.submittedAt },
            second.pending.map { it.original.submittedAt },
        )
    }

    @Test
    fun exactProgressCanRestoreAnOriginalWhoseSourceWasAlreadyRemoved() {
        val f = RecipientChatPresentationFixture()
        val result =
            f.success(
                f.read(
                    progress =
                        listOf(CollaborationSpaceRecipientMessageProgress(f.first, f.record()))
                )
            )
        assertEquals(f.first, result.pending.single().original)
        assertTrue(result.pending.single().durable)
        assertEquals(f.record(), result.pending.single().record)
    }

    @Test
    fun missingOrDifferentCanonicalFactsCannotInterpretAStoredCommand() {
        val f = RecipientChatPresentationFixture()
        val progress = listOf(CollaborationSpaceRecipientMessageProgress(f.first, f.record()))
        assertIs<AppResult.Failed>(
            f.read(source = listOf(f.first), progress = progress, conversation = null)
        )
        assertIs<AppResult.Failed>(
            f.read(
                source = listOf(f.first),
                progress = progress,
                conversation = f.conversation.copy(id = chatId(507)),
            )
        )
        assertIs<AppResult.Failed>(
            f.read(
                source = listOf(f.first),
                conversation = f.conversation.copy(spaceId = chatId(508)),
            )
        )
    }
}
