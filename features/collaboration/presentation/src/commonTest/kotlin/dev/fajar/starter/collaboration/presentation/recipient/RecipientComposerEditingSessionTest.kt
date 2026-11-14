@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.FailureKind
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class RecipientComposerEditingSessionTest {
    @Test
    fun rapidSendsClearImmediatelyAndPreserveTypingAfterTheHeldFirstWrite() = runTest {
        val f = RecipientComposerActorFixture(this)
        f.actor.bind(null)
        val release = CompletableDeferred<Unit>()
        f.beforeSubmit = { release.await() }
        val originals =
            listOf("AAA", "BBB", "CCC", "DDD").map { text ->
                f.actor.change(text)
                assertNotNull(f.actor.send()).also { assertEquals("", f.actor.state.text) }
            }
        assertTrue(f.submits.isEmpty())
        f.actor.change("Later typing")
        runCurrent()
        assertEquals(listOf(originals.first()), f.submits.map { it.original })
        assertEquals("Later typing", f.actor.state.text)
        release.complete(Unit)
        assertTrue(f.actor.flush())
        assertEquals(originals, f.submits.map { it.original })
        assertTrue(
            f.submits.all { it.access == f.access && it.original.original.peerAccountId == f.peer }
        )
        assertEquals("Later typing", f.persisted?.text)
        assertTrue(f.actor.state.submissions.all { it.durable })
    }

    @Test
    fun anInFlightAutosaveFinishesBeforeSendComparesTheStoredEditor() = runTest {
        val f = RecipientComposerActorFixture(this)
        f.actor.bind(null)
        val release = CompletableDeferred<Unit>()
        f.beforeSave = { release.await() }
        f.actor.change("Initial typing")
        advanceTimeBy(250)
        runCurrent()
        val saving = f.saves.single()
        f.actor.change("Captured message")
        val original = assertNotNull(f.actor.send())
        f.actor.change("New typing")
        runCurrent()
        assertTrue(f.submits.isEmpty())
        release.complete(Unit)
        assertTrue(f.actor.flush())
        assertEquals(saving.replacement, f.submits.single().expected)
        assertEquals(original, f.submits.single().original)
        assertEquals("New typing", f.persisted?.text)
    }

    @Test
    fun interruptedSendDoesNotRetryOnTypingOrTimeAndExplicitRetryKeepsItsIdentity() = runTest {
        val f = RecipientComposerActorFixture(this)
        f.actor.bind(null)
        f.submitFailure = f.storage
        f.actor.change("Original")
        val original = assertNotNull(f.actor.send())
        runCurrent()
        val request = f.submits.single()
        f.actor.change("Independent typing")
        f.now += 30.seconds
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(listOf(request), f.submits)
        assertFalse(f.actor.flush())
        f.submitFailure = null
        f.actor.retry()
        assertTrue(f.actor.flush())
        assertEquals(listOf(request, request), f.submits)
        assertEquals(original, f.retained[original.id])
        assertEquals("Independent typing", f.persisted?.text)
    }

    @Test
    fun uncertainAutosaveRetainsItsExactRequestBeforeAnyQueuedSend() = runTest {
        val f = RecipientComposerActorFixture(this)
        f.actor.bind(null)
        f.saveFailure = f.storage
        f.actor.change("Saved before interruption")
        advanceTimeBy(250)
        runCurrent()
        val request = f.saves.single()
        f.actor.change("Message after interruption")
        val original = assertNotNull(f.actor.send())
        f.actor.change("Draft after message")
        runCurrent()
        assertTrue(f.submits.isEmpty())
        assertEquals(1, f.saves.size)
        f.saveFailure = null
        f.actor.retry()
        assertTrue(f.actor.flush())
        assertEquals(request, f.saves[1])
        assertEquals(request.replacement, f.submits.single().expected)
        assertEquals(original, f.submits.single().original)
        assertEquals("Draft after message", f.persisted?.text)
    }

    @Test
    fun closingTheOwnerFencesANonCancellableLateSubmissionAndItsUiCallback() = runTest {
        val f = RecipientComposerActorFixture(this)
        f.actor.bind(null)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeSubmit = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        f.actor.change("Old owner")
        val original = assertNotNull(f.actor.send())
        entered.await()
        f.actor.close()
        val count = f.publications.size
        release.complete(Unit)
        runCurrent()
        assertEquals(RecipientComposerEditingState(), f.actor.state)
        assertEquals(count, f.publications.size)
        assertEquals(original, f.submits.single().original)
        f.actor.bind(null)
        f.actor.change("Closed")
        assertNull(f.actor.send())
        assertFalse(f.actor.flush())
    }

    @Test
    fun theSixteenthPendingGestureKeepsTheNextUnacceptedText() = runTest {
        val f = RecipientComposerActorFixture(this)
        f.actor.bind(null)
        val release = CompletableDeferred<Unit>()
        f.beforeSubmit = { release.await() }
        repeat(16) {
            f.actor.change("Message $it")
            assertNotNull(f.actor.send())
            runCurrent()
        }
        f.actor.change("Keep this input")
        assertNull(f.actor.send())
        assertEquals("Keep this input", f.actor.state.text)
        release.complete(Unit)
        assertTrue(f.actor.flush())
        assertEquals(16, f.submits.size)
        assertEquals("Keep this input", f.persisted?.text)
    }

    @Test
    fun capturedOrderSurvivesEqualAndBackwardClocksIncludingRetainedEarlierGestures() = runTest {
        val f = RecipientComposerActorFixture(this)
        val retainedTime = f.now + 20.seconds
        f.actor.bind(null, latestSubmittedAt = retainedTime)
        val originals =
            (0..2).map {
                f.actor.change("Message $it")
                f.now -= 5.seconds
                assertNotNull(f.actor.send())
            }
        assertTrue(originals.first().submittedAt > retainedTime)
        assertTrue(originals.zipWithNext().all { (a, b) -> a.submittedAt < b.submittedAt })
        assertEquals(3, originals.map { it.id }.distinct().size)
        assertTrue(f.actor.flush())
    }

    @Test
    fun anotherObservationCannotReplaceTypedInputOrCapturedOriginals() = runTest {
        val f = RecipientComposerActorFixture(this)
        val draft = CollaborationSpaceRecipientDraft(f.peer, chatId(500), "Restored", f.now)
        f.actor.bind(draft)
        assertEquals("Restored", f.actor.state.text)
        val original = assertNotNull(f.actor.send())
        f.actor.change("Keep typing")
        f.actor.bind(draft.copy(text = "Late stale snapshot"))
        assertEquals("Keep typing", f.actor.state.text)
        assertEquals(original, f.actor.state.submissions.single().original)
        assertTrue(f.actor.flush())
    }

    @Test
    fun onlyTheExactDurableProjectionCanRetireProvisionalFeedback() = runTest {
        val f = RecipientComposerActorFixture(this)
        f.actor.bind(null)
        val release = CompletableDeferred<Unit>()
        f.beforeSubmit = { release.await() }
        f.actor.change("Original")
        val original = assertNotNull(f.actor.send())
        runCurrent()
        f.actor.represented(setOf(original))
        assertEquals(1, f.actor.state.submissions.size)
        release.complete(Unit)
        assertTrue(f.actor.flush())
        f.actor.represented(
            setOf(original.copy(original = original.original.copy(text = "Wrong body")))
        )
        assertEquals(1, f.actor.state.submissions.size)
        f.actor.represented(setOf(original))
        assertTrue(f.actor.state.submissions.isEmpty())
    }

    @Test
    fun onlyANeverAttemptedGestureCanBeDiscardedWithoutDurableCancellation() = runTest {
        val f = RecipientComposerActorFixture(this)
        f.actor.bind(null)
        val release = CompletableDeferred<Unit>()
        f.beforeSubmit = { release.await() }
        f.actor.change("Attempted")
        val first = assertNotNull(f.actor.send())
        runCurrent()
        f.actor.change("Not attempted")
        val second = assertNotNull(f.actor.send())
        assertFalse(f.actor.discardUnsubmitted(first))
        assertTrue(f.actor.discardUnsubmitted(second))
        release.complete(Unit)
        assertTrue(f.actor.flush())
        assertEquals(listOf(first), f.submits.map { it.original })
    }

    @Test
    fun settlingOneOriginalDoesNotWaitForLaterTyping() = runTest {
        val f = RecipientComposerActorFixture(this)
        f.actor.bind(null)
        val release = CompletableDeferred<Unit>()
        f.beforeSave = { release.await() }
        f.actor.change("Message")
        val original = assertNotNull(f.actor.send())
        f.actor.change("Later typing")
        val settled = async { f.actor.settled(original) }
        runCurrent()
        assertTrue(settled.isCompleted)
        assertTrue(settled.await())
        assertTrue(f.actor.state.saving)
        release.complete(Unit)
        assertTrue(f.actor.flush())
    }

    @Test
    fun changedSuccessCannotAcknowledgeTheCapturedOriginal() = runTest {
        val f = RecipientComposerActorFixture(this)
        f.actor.bind(null)
        f.mapSubmitted = { it.copy(nextRevision = chatId(999)) }
        f.actor.change("Original")
        val original = assertNotNull(f.actor.send())
        runCurrent()
        assertFalse(f.actor.state.submissions.single().durable)
        assertEquals(FailureKind.Unexpected, f.actor.state.failure?.kind)
        f.mapSubmitted = { it }
        f.actor.retry()
        assertTrue(f.actor.flush())
        assertEquals(listOf(original, original), f.submits.map { it.original })
    }

    @Test
    fun changedDraftSuccessKeepsItsExactWriteUntilExplicitRetry() = runTest {
        val f = RecipientComposerActorFixture(this)
        f.actor.bind(null)
        f.mapSaved = { it.copy(text = "Substituted") }
        f.actor.change("Keep this text")
        advanceTimeBy(250)
        runCurrent()
        assertEquals(FailureKind.Unexpected, f.actor.state.failure?.kind)
        val request = f.saves.single()
        f.mapSaved = { it }
        f.actor.retry()
        assertTrue(f.actor.flush())
        assertEquals(listOf(request, request), f.saves)
        assertEquals("Keep this text", f.actor.state.text)
    }
}
