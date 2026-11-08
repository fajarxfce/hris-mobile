package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatComposerEditingSessionTest {
    @Test
    fun unsubmittedGestureCanBeDiscardedWhileAnotherLocalWriteIsRunning() = runTest {
        val f = ComposerEditingFixture(this)
        val release = CompletableDeferred<Unit>()
        f.beforeSubmit = { release.await() }
        f.session.change("A")
        val a = assertNotNull(f.session.send())
        runCurrent()
        f.session.change("B")
        val b = assertNotNull(f.session.send())
        f.session.change("Keep C")
        assertFalse(f.session.discardUnsubmitted(a))
        assertTrue(f.session.discardUnsubmitted(b))
        release.complete(Unit)
        assertTrue(f.session.flush())
        assertEquals(listOf(a), f.submits.map { it.original })
        assertEquals("Keep C", f.persisted?.text)
    }

    @Test
    fun waitingForOneSubmissionDoesNotWaitForTheNextTypingOrNetwork() = runTest {
        val f = ComposerEditingFixture(this)
        val release = CompletableDeferred<Unit>()
        f.beforeSubmit = { release.await() }
        f.session.change("A")
        val a = assertNotNull(f.session.send())
        runCurrent()
        val settled = async { f.session.settled(a) }
        runCurrent()
        assertFalse(settled.isCompleted)
        f.session.change("Keep B")
        release.complete(Unit)
        assertTrue(settled.await())
        assertEquals("Keep B", f.session.state.text)
        assertTrue(f.session.flush())
    }

    @Test
    fun rebindingCancelsTheOldSubmissionWaitWithoutGrantingTheNewOwnerItsResult() = runTest {
        val f = ComposerEditingFixture(this)
        val release = CompletableDeferred<Unit>()
        f.beforeSubmit = { release.await() }
        f.session.change("A")
        val a = assertNotNull(f.session.send())
        runCurrent()
        val settled = async { f.session.settled(a) }
        runCurrent()
        f.session.reset(f.access.copy(accountId = f.id(998)), null)
        assertFalse(settled.await())
        release.complete(Unit)
    }

    @Test
    fun severalSendsClearImmediatelyWhileNewerTypingSurvivesTheirLateCommits() = runTest {
        val f = ComposerEditingFixture(this)
        val release = CompletableDeferred<Unit>()
        f.beforeSubmit = { release.await() }
        f.session.change("A")
        val a = assertNotNull(f.session.send())
        assertEquals("", f.session.state.text)
        assertEquals(listOf(a), f.session.state.submissions.map { it.original })
        runCurrent()
        f.session.change("B")
        val b = assertNotNull(f.session.send())
        f.session.change("Keep typing C")
        assertEquals("Keep typing C", f.session.state.text)
        assertTrue(f.submits.size == 1)
        release.complete(Unit)
        assertTrue(f.session.flush())
        assertEquals(listOf(a, b), f.submits.map { it.original })
        assertTrue(a.submittedAt < b.submittedAt)
        assertEquals("Keep typing C", f.session.state.text)
        assertEquals("Keep typing C", f.persisted?.text)
        assertEquals(a.nextDraft, f.submits[1].expected)
        assertTrue(f.session.state.submissions.all { it.durable })
    }

    @Test
    fun inFlightAutosaveFinishesBeforeTheSendChoosesItsStoredSlot() = runTest {
        val f = ComposerEditingFixture(this)
        val release = CompletableDeferred<Unit>()
        f.beforeSave = { release.await() }
        f.session.change("Earlier text")
        advanceTimeBy(250)
        runCurrent()
        assertEquals(1, f.saves.size)
        val savingRevision = f.saves.single().revision
        f.session.change("Complete A")
        val a = assertNotNull(f.session.send())
        f.session.change("Newer B")
        runCurrent()
        assertTrue(f.submits.isEmpty())
        release.complete(Unit)
        assertTrue(f.session.flush())
        assertEquals(savingRevision, f.submits.single().expected?.revision)
        assertEquals("Complete A", a.original.text)
        assertEquals("Newer B", f.session.state.text)
        assertEquals("Newer B", f.persisted?.text)
    }

    @Test
    fun aLostSubmitReplyRetriesTheSameGestureAndPreservesNewerInput() = runTest {
        val f = ComposerEditingFixture(this)
        f.submitFailure = f.storage
        f.session.change("Original A")
        val a = assertNotNull(f.session.send())
        runCurrent()
        f.session.change("New B")
        val request = f.submits.single()
        assertFalse(f.session.flush())
        assertEquals(2, f.submits.size)
        f.submitFailure = null
        f.session.retry()
        assertTrue(f.session.flush())
        assertEquals(listOf(request, request, request), f.submits)
        assertEquals(listOf(a.id), f.retained.keys.toList())
        assertEquals("New B", f.persisted?.text)
        assertEquals("New B", f.session.state.text)
    }

    @Test
    fun aLostAutosaveReplyMustResolveItsExactWriteBeforeSubmitting() = runTest {
        val f = ComposerEditingFixture(this)
        f.saveFailure = f.storage
        f.session.change("Saved earlier A")
        advanceTimeBy(250)
        runCurrent()
        val first = f.saves.single()
        f.session.change("Final A")
        val original = assertNotNull(f.session.send())
        f.session.change("Keep B")
        runCurrent()
        assertTrue(f.submits.isEmpty())
        f.saveFailure = null
        f.session.retry()
        assertTrue(f.session.flush())
        assertEquals(first, f.saves[1])
        assertEquals(first.revision, f.submits.single().expected?.revision)
        assertEquals(original, f.submits.single().original)
        assertEquals("Keep B", f.session.state.text)
    }

    @Test
    fun aLateCancelledCompletionCannotReplaceTheNewAccountsEditor() = runTest {
        val f = ComposerEditingFixture(this)
        val release = CompletableDeferred<Unit>()
        f.beforeSubmit = { withContext(NonCancellable) { release.await() } }
        f.session.change("Old account original")
        f.session.send()
        runCurrent()
        val draft = CollaborationDraft(f.conversation, f.id(999), "New account draft", f.now)
        f.session.reset(f.access.copy(accountId = f.id(998)), draft)
        val newState = f.session.state
        release.complete(Unit)
        runCurrent()
        assertEquals(newState, f.session.state)
        assertEquals("New account draft", f.session.state.text)
        assertTrue(f.session.state.submissions.isEmpty())
    }

    @Test
    fun unrelatedDraftObservationsDoNotReplaceTypedTextAndLegacySuccessorCanOpen() = runTest {
        val f = ComposerEditingFixture(this)
        f.session.change("New text")
        f.session.bind(
            f.access,
            CollaborationDraft(f.conversation, f.id(991), "Old snapshot", f.now),
        )
        assertEquals("New text", f.session.state.text)
        val frozen =
            CollaborationDraft(
                f.conversation,
                f.id(992),
                "Legacy original",
                f.now,
                submissionId = f.id(993),
            )
        f.session.reset(f.access, frozen)
        f.session.change("Must not replace frozen body")
        assertNull(f.session.send())
        assertEquals("Legacy original", f.session.state.text)
        val next = frozen.copy(revision = f.id(994), text = "", submissionId = null)
        f.session.bind(f.access, next)
        f.session.change("New after recovery")
        assertNotNull(f.session.send())
        f.session.clear()
    }

    @Test
    fun provisionalFeedbackIsRetainedUntilTheExactDurableOriginalIsVisible() = runTest {
        val f = ComposerEditingFixture(this)
        val release = CompletableDeferred<Unit>()
        f.beforeSubmit = { release.await() }
        f.session.change("A")
        val a = assertNotNull(f.session.send())
        runCurrent()
        f.session.visible(setOf(a))
        assertEquals(1, f.session.state.submissions.size)
        release.complete(Unit)
        assertTrue(f.session.flush())
        f.session.visible(setOf(a.copy(nextRevision = f.id(990))))
        assertEquals(1, f.session.state.submissions.size)
        f.session.visible(setOf(a))
        assertTrue(f.session.state.submissions.isEmpty())
    }

    @Test
    fun boundedUncommittedQueueKeepsTheTextOfTheNextUnacceptedSend() = runTest {
        val f = ComposerEditingFixture(this)
        val release = CompletableDeferred<Unit>()
        f.beforeSubmit = { release.await() }
        repeat(16) { index ->
            f.session.change("Message $index")
            assertNotNull(f.session.send())
        }
        f.session.change("Keep message 17")
        assertNull(f.session.send())
        assertEquals("Keep message 17", f.session.state.text)
        assertEquals(16, f.session.state.submissions.size)
        release.complete(Unit)
        assertTrue(f.session.flush())
        assertEquals(16, f.submits.size)
        assertEquals("Keep message 17", f.persisted?.text)
    }
}

private class ComposerEditingFixture(scope: CoroutineScope) {
    fun id(value: Int) = "00000000-0000-0000-0000-" + value.toString().padStart(12, '0')

    val conversation = id(1)
    val access = CollaborationLocalAccess("local", "https://test.invalid", id(2), id(3))
    val now = Instant.parse("2026-11-08T00:00:00Z")
    val storage = Failure(FailureKind.Storage, "Unavailable")
    var persisted: CollaborationDraft? = null
    val retained = mutableMapOf<String, CollaborationComposerSubmission>()
    val saves = mutableListOf<ChatComposerDraftWrite>()
    val submits = mutableListOf<ChatComposerSubmissionWrite>()
    var beforeSave: suspend () -> Unit = {}
    var beforeSubmit: suspend () -> Unit = {}
    var saveFailure: Failure? = null
    var submitFailure: Failure? = null
    var sequence = 100
    val session =
        ChatComposerEditingSession(
            scope,
            conversation,
            save = { request ->
                saves += request
                beforeSave()
                if (persisted?.revision != request.revision) {
                    assertEquals(request.expected, persisted)
                    persisted =
                        CollaborationDraft(
                            conversation,
                            request.revision,
                            request.text,
                            now,
                            replyToEventId = request.replyToEventId,
                        )
                }
                saveFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(requireNotNull(persisted))
            },
            submit = { request ->
                submits += request
                beforeSubmit()
                if (request.original.id !in retained) {
                    assertEquals(request.expected, persisted)
                    retained[request.original.id] = request.original
                    persisted = request.original.nextDraft
                }
                submitFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(request.original)
            },
            onState = {},
            clock =
                object : Clock {
                    override fun now() = now
                },
            revision = { id(++sequence) },
        )

    init {
        session.bind(access, null)
    }
}
