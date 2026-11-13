@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class SpaceChatComposerEditingSessionTest {
    @Test
    fun retriesKeepTheExactTypedOwnerEditAndCapturedTime() = runTest {
        val f = SpaceComposerActorFixture(this)
        f.saveFailure = f.storage
        f.actor.change("Exact edit")
        advanceTimeBy(250)
        runCurrent()
        val original = f.saves.single()
        f.now += 30.seconds
        f.saveFailure = null
        f.actor.retry()
        assertTrue(f.actor.flush())
        assertEquals(listOf(original, original), f.saves)
        assertEquals(f.access, original.access)
        assertEquals(original.savedAt, f.persisted?.savedAt)
    }

    @Test
    fun changingTheGroupCannotPublishALateCancelledOldSubmission() = runTest {
        val f = SpaceComposerActorFixture(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeSubmit = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        f.actor.change("Old Group")
        val original = assertNotNull(f.actor.send())
        runCurrent()
        entered.await()
        val other = f.access.copy(spaceId = chatId(900))
        val draft = CollaborationDraft(f.conversation, chatId(901), "Other Group draft", f.now)
        f.actor.reset(other, draft)
        val replacement = f.actor.state
        release.complete(Unit)
        runCurrent()
        assertEquals(replacement, f.actor.state)
        assertEquals(original, f.submits.single().original)
        assertEquals(f.access, f.submits.single().access)
        assertTrue(f.actor.state.submissions.isEmpty())
    }

    @Test
    fun aNewLocalSessionKeepsItsEditorAfterAnOldWriteFinishes() = runTest {
        val f = SpaceComposerActorFixture(this)
        val release = CompletableDeferred<Unit>()
        f.beforeSave = { withContext(NonCancellable) { release.await() } }
        f.actor.change("Old session")
        advanceTimeBy(250)
        runCurrent()
        val restored =
            CollaborationDraft(f.conversation, chatId(902), "Restored owned draft", f.now)
        f.actor.reset(f.access.copy(sessionId = "new-session"), restored)
        val expected = f.actor.state
        release.complete(Unit)
        runCurrent()
        assertEquals(expected, f.actor.state)
        assertEquals("Restored owned draft", f.actor.state.text)
        assertEquals("session", f.saves.single().access.sessionId)
    }

    @Test
    fun theBoundedGroupQueueKeepsTheNextTextWhenItCannotAcceptAnotherGesture() = runTest {
        val f = SpaceComposerActorFixture(this)
        val release = CompletableDeferred<Unit>()
        f.beforeSubmit = { release.await() }
        repeat(16) {
            f.actor.change("Message $it")
            assertNotNull(f.actor.send())
            runCurrent()
        }
        f.actor.change("Keep next text")
        assertNull(f.actor.send())
        assertEquals("Keep next text", f.actor.state.text)
        assertEquals(16, f.actor.state.submissions.size)
        release.complete(Unit)
        assertTrue(f.actor.flush())
        assertEquals(16, f.submits.size)
        assertTrue(f.submits.all { it.access == f.access })
        assertEquals("Keep next text", f.persisted?.text)
    }
}

private class SpaceComposerActorFixture(scope: CoroutineScope) {
    val access =
        CollaborationSpaceLocalAccess("session", "https://example.test/", chatId(1), chatId(2))
    val conversation = chatId(3)
    var now = Instant.parse("2026-11-13T00:00:00Z")
    val storage = Failure(FailureKind.Storage, "Fictional write interruption")
    var persisted: CollaborationDraft? = null
    var saveFailure: Failure? = null
    var beforeSave: suspend () -> Unit = {}
    var beforeSubmit: suspend () -> Unit = {}
    val saves = mutableListOf<ChatComposerDraftWrite<CollaborationSpaceLocalAccess>>()
    val submits = mutableListOf<ChatComposerSubmissionWrite<CollaborationSpaceLocalAccess>>()
    var revision = 100
    val actor =
        ChatComposerEditingSession<CollaborationSpaceLocalAccess>(
            scope,
            conversation,
            save = { request ->
                saves += request
                beforeSave()
                saveFailure?.let { AppResult.Failed(it) }
                    ?: run {
                        persisted =
                            CollaborationDraft(
                                conversation,
                                request.revision,
                                request.text,
                                request.savedAt,
                                replyToEventId = request.replyToEventId,
                            )
                        AppResult.Success(requireNotNull(persisted))
                    }
            },
            submit = { request ->
                submits += request
                beforeSubmit()
                persisted = request.original.nextDraft
                AppResult.Success(request.original)
            },
            onState = {},
            clock =
                object : Clock {
                    override fun now() = this@SpaceComposerActorFixture.now
                },
            revision = { chatId(++revision) },
        )

    init {
        actor.bind(access, null)
    }
}
