package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class CollaborationQuotedDraftTest {
    private val target = "\$quoted-original"

    @Test
    fun offlineTypingAndLostSaveResponseKeepTheOriginalQuoteUntilExplicitlyCleared() = runTest {
        val f = DraftIntentFixture()
        f.f.setup.now += 365.days
        f.writeFailure = f.f.storage
        assertIs<AppResult.Failed>(f.edit(replyToEventId = target))
        val original = f.slot.value!!
        f.writeFailure = null
        assertEquals(AppResult.Success(original), f.edit(replyToEventId = target))
        assertIs<AppResult.Failed>(f.edit(replyToEventId = "\$another-original"))
        val typed =
            assertIs<AppResult.Success<CollaborationDraft>>(f.edit(original, id(80), "More text"))
                .value
        assertEquals(target, typed.replyToEventId)
        assertEquals(AppResult.Success(typed), f.observe().first())
        val cleared =
            assertIs<AppResult.Success<CollaborationDraft>>(
                    f.edit(typed, id(81), typed.text, replyToEventId = null)
                )
                .value
        assertNull(cleared.replyToEventId)
        assertEquals(typed.text, cleared.text)
        assertEquals(0, f.f.setup.calls)
    }

    @Test
    fun interruptedFreezeAndQueuePreserveTheQuoteAndRejectSameTextWithAnotherTarget() = runTest {
        val f = DraftIntentFixture()
        val edited =
            assertIs<AppResult.Success<CollaborationDraft>>(f.edit(replyToEventId = target)).value
        f.writeFailure = f.f.storage
        assertIs<AppResult.Failed>(f.prepare(edited))
        val frozen = f.slot.value!!
        f.writeFailure = null
        assertEquals(AppResult.Success(frozen), f.prepare(edited))
        assertIs<AppResult.Failed>(f.prepare(edited.copy(replyToEventId = "\$different")))
        f.f.insertResult = AppResult.Failed(f.f.storage)
        assertIs<AppResult.Failed>(f.queue(frozen))
        assertEquals(target, f.f.records.value.single().intent.replyToEventId)
        assertIs<AppResult.Failed>(f.queue(frozen.copy(replyToEventId = "\$different")))
        assertEquals(1, f.f.insertions)
        val cleared = assertIs<AppResult.Success<CollaborationDraft?>>(f.confirm(frozen)).value!!
        assertEquals("", cleared.text)
        assertNull(cleared.replyToEventId)
        assertNull(cleared.submissionId)
    }

    @Test
    fun acknowledgementCannotClearADraftForAnotherQuote() = runTest {
        val f = DraftIntentFixture()
        val edited =
            assertIs<AppResult.Success<CollaborationDraft>>(f.edit(replyToEventId = target)).value
        val frozen = assertIs<AppResult.Success<CollaborationDraft>>(f.prepare(edited)).value
        val sent = assertIs<AppResult.Success<CollaborationMessageRecord>>(f.queue(frozen)).value
        f.commandOverride =
            AppResult.Success(sent.copy(intent = sent.intent.copy(replyToEventId = "\$other")))
        assertIs<AppResult.Failed>(f.confirm(frozen))
        assertEquals(frozen, f.slot.value)
        f.commandOverride = null
        val concurrent = frozen.copy(replyToEventId = "\$newer-quote")
        f.slot.value = concurrent
        assertEquals(AppResult.Success(concurrent), f.confirm(frozen))
        assertEquals(concurrent, f.slot.value)
    }

    @Test
    fun invalidQuoteNeverTouchesStorageOrCreatesAnOfflineIntent() = runTest {
        for (target in
            listOf(
                "",
                "not-an-event",
                "\$bad id",
                "\$é",
                "\$\uD800",
                "\$",
                "\$" + "x".repeat(255),
            )) {
            val draft = DraftIntentFixture()
            assertIs<AppResult.Failed>(draft.edit(replyToEventId = target))
            assertEquals(0, draft.reads)
            assertEquals(0, draft.writes)
            val message = MessageIntentFixture()
            assertIs<AppResult.Failed>(message.queue(replyToEventId = target))
            assertEquals(0, message.messageReads)
            assertEquals(0, message.insertions)
        }
    }

    private fun id(value: Int) = "00000000-0000-0000-0000-" + value.toString().padStart(12, '0')
}
