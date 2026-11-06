@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.thread

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ChatThreadDraftTest {
    @Test
    fun technicalContentionRetriesOriginalTextAndRevisionWithoutAskingToReload() = runTest {
        withDraft { f, vm, _, _ ->
            f.beforeDraftWriteFailure =
                Failure(FailureKind.Conflict, "Busy", code = "client.collaboration_draft_busy")
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Keep my unsaved text"))
            advanceUntilIdle()
            val attempted = f.draftWriteAttempts.single()
            assertEquals("Keep my unsaved text", vm.state.value.draft)
            assertEquals("client.collaboration_draft_busy", vm.state.value.draftFailure?.code)
            assertNull(f.draftSlot.value)
            assertTrue(f.draftWrites.isEmpty())
            f.beforeDraftWriteFailure = null
            vm.onEvent(ChatThreadEvent.DraftRetryRequested)
            advanceUntilIdle()
            assertNull(vm.state.value.draftDialog)
            assertNull(vm.state.value.draftFailure)
            assertEquals(2, f.draftWriteAttempts.size)
            val saved = f.draftWrites.single()
            assertEquals(attempted.revision, saved.revision)
            assertEquals(attempted.text, saved.text)
            assertEquals("Keep my unsaved text", vm.state.value.draft)
            assertEquals(saved, f.draftSlot.value)
        }
    }

    @Test
    fun pendingInitialReadDoesNotAllowTypingOverAnExistingDraft() = runTest {
        withDraft { f, vm, _, _ ->
            val ready = CompletableDeferred<Unit>()
            f.draftSlot.value = f.savedDraft("Saved text")
            f.beforeDraft = { ready.await() }
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            assertFalse(vm.state.value.canCompose)
            vm.onEvent(ChatThreadEvent.DraftChanged("Too early"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertTrue(f.insertions.isEmpty())
            ready.complete(Unit)
            runCurrent()
            assertEquals("Saved text", vm.state.value.draft)
            assertTrue(vm.state.value.canCompose)
        }
    }

    @Test
    fun latestInputCoalescesWhileOneAdmittedWriteIsSuspended() = runTest {
        withDraft { f, vm, _, _ ->
            val release = CompletableDeferred<Unit>()
            f.afterDraftWrite = { if (f.draftWrites.size == 1) release.await() }
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("First"))
            advanceTimeBy(250)
            runCurrent()
            assertEquals(1, f.draftWrites.size)
            repeat(100) { vm.onEvent(ChatThreadEvent.DraftChanged("Latest $it")) }
            runCurrent()
            assertEquals("Latest 99", vm.state.value.draft)
            assertEquals(1, f.draftWrites.size)
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf("First", "Latest 99"), f.draftWrites.map { it.text })
            assertEquals("Latest 99", f.draftSlot.value!!.text)
            assertFalse(vm.state.value.draftSaving)
        }
    }

    @Test
    fun lostWriteReplyRetriesTheSameRevisionBeforeSavingLaterInput() = runTest {
        withDraft { f, vm, _, _ ->
            f.draftWriteFailure = f.base.network
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Uncertain"))
            advanceUntilIdle()
            val uncertain = f.draftSlot.value!!
            assertNotNull(vm.state.value.draftFailure)
            f.draftWriteFailure = null
            vm.onEvent(ChatThreadEvent.DraftChanged("New input"))
            advanceUntilIdle()
            assertEquals(listOf(uncertain, f.draftSlot.value), f.draftWrites)
            assertEquals("New input", f.draftSlot.value!!.text)
            assertNull(vm.state.value.draftFailure)
        }
    }

    @Test
    fun lateStorageEmissionCannotReplaceEditedOrAcknowledgedText() = runTest {
        withDraft { f, vm, _, _ ->
            val emissions =
                MutableStateFlow<AppResult<CollaborationDraft?>>(AppResult.Success(null))
            f.draftFlow = emissions
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Current text"))
            advanceUntilIdle()
            emissions.value = AppResult.Success(f.savedDraft("Old text"))
            runCurrent()
            assertEquals("Current text", vm.state.value.draft)
            assertEquals("Current text", f.draftSlot.value!!.text)
        }
    }

    @Test
    fun savedDraftReopensInANewViewModelWithExactWhitespace() = runTest {
        withDraft { f, vm, store, _ ->
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("  saved\n🙂  "))
            advanceUntilIdle()
            store.clear()
            val reopened = f.model()
            val owner = ViewModelStore().also { it.put("reopened", reopened) }
            try {
                reopened.onEvent(ChatThreadEvent.Entered())
                runCurrent()
                assertEquals("  saved\n🙂  ", reopened.state.value.draft)
                assertTrue(reopened.state.value.canCompose)
            } finally {
                owner.clear()
            }
        }
    }

    @Test
    fun deathAfterFreezeBeforeQueueReopensTheOriginalSubmissionId() = runTest {
        withDraft { f, vm, store, _ ->
            f.afterDraftWrite = { if (it.submissionId != null) awaitCancellation() }
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Frozen before death"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            val frozen = f.draftSlot.value!!
            assertNotNull(frozen.submissionId)
            assertTrue(f.insertions.isEmpty())
            store.clear()
            f.afterDraftWrite = {}
            val reopened = f.model()
            val owner = ViewModelStore().also { it.put("reopened", reopened) }
            try {
                reopened.onEvent(ChatThreadEvent.Entered())
                runCurrent()
                assertTrue(reopened.state.value.submissionPending)
                assertFalse(reopened.state.value.canCompose)
                reopened.onEvent(ChatThreadEvent.SendRequested)
                runCurrent()
                assertEquals(frozen.submissionId, f.insertions.single().id)
                assertEquals(frozen.text, f.insertions.single().text)
                assertEquals("", reopened.state.value.draft)
            } finally {
                owner.clear()
            }
        }
    }

    @Test
    fun deathAfterQueueCommitBeforeAcknowledgementDoesNotInsertAgain() = runTest {
        withDraft { f, vm, store, _ ->
            f.afterInsert = { awaitCancellation() }
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Queued before death"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            val queued = f.records.value.single()
            store.clear()
            f.afterInsert = {}
            val reopened = f.model()
            val owner = ViewModelStore().also { it.put("reopened", reopened) }
            try {
                reopened.onEvent(ChatThreadEvent.Entered())
                runCurrent()
                reopened.onEvent(ChatThreadEvent.SendRequested)
                runCurrent()
                assertEquals(listOf(queued.intent), f.insertions)
                assertEquals("", reopened.state.value.draft)
                assertFalse(reopened.state.value.submissionPending)
            } finally {
                owner.clear()
            }
        }
    }

    @Test
    fun lostClearReplyThenNewerDraftKeepsNewTextAfterRetry() = runTest {
        withDraft { f, vm, _, _ ->
            f.afterDraftWrite = { if (it.text.isEmpty()) f.draftWriteFailure = f.base.network }
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Original send"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertTrue(vm.state.value.submissionPending)
            assertEquals("", f.draftSlot.value!!.text)
            val newer = f.savedDraft("Newer draft")
            f.draftSlot.value = newer
            f.draftWriteFailure = null
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            assertEquals("Newer draft", vm.state.value.draft)
            assertEquals(newer, f.draftSlot.value)
            assertEquals(1, f.insertions.size)
        }
    }

    @Test
    fun rejectedCharactersCanBeCorrectedWithoutRetryingAnInvalidBodyForever() = runTest {
        withDraft { f, vm, _, _ ->
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Bad\u0000text"))
            advanceUntilIdle()
            assertNotNull(vm.state.value.draftFailure)
            assertTrue(f.draftWrites.isEmpty())
            vm.onEvent(ChatThreadEvent.DraftChanged("Corrected"))
            advanceUntilIdle()
            assertEquals("Corrected", f.draftSlot.value!!.text)
            assertNull(vm.state.value.draftFailure)
        }
    }

    @Test
    fun navigationFlushesTheDebounceAndFailedSaveRequiresExplicitDiscard() = runTest {
        withDraft { f, vm, _, effects ->
            f.draftWriteFailure = f.base.network
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Before back"))
            vm.onEvent(ChatThreadEvent.BackRequested)
            runCurrent()
            assertEquals("Before back", f.draftSlot.value!!.text)
            assertTrue(effects.isEmpty())
            assertEquals(ChatDraftDialog.LEAVE, vm.state.value.draftDialog)
            vm.onEvent(ChatThreadEvent.DraftDialogConfirmed)
            runCurrent()
            assertEquals(listOf<ChatThreadEffect>(ChatThreadEffect.Back), effects)
        }
    }

    @Test
    fun concurrentDraftIsNotOverwrittenAndReloadRequiresConfirmation() = runTest {
        withDraft { f, vm, _, _ ->
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            val concurrent = f.savedDraft("Other window")
            f.draftSlot.value = concurrent
            vm.onEvent(ChatThreadEvent.DraftChanged("My unsaved text"))
            advanceUntilIdle()
            assertEquals("client.collaboration_draft_changed", vm.state.value.draftFailure?.code)
            assertEquals(concurrent, f.draftSlot.value)
            vm.onEvent(ChatThreadEvent.DraftRetryRequested)
            runCurrent()
            assertEquals(ChatDraftDialog.RELOAD, vm.state.value.draftDialog)
            assertEquals("My unsaved text", vm.state.value.draft)
            vm.onEvent(ChatThreadEvent.DraftDialogConfirmed)
            runCurrent()
            assertEquals("Other window", vm.state.value.draft)
            assertTrue(vm.state.value.canCompose)
        }
    }

    @Test
    fun hiddenOwnerLossCancelsAdmittedWriteAndCannotPublishAnotherAccountsDraft() = runTest {
        withDraft { f, vm, _, effects ->
            val release = CompletableDeferred<Unit>()
            f.afterDraftWrite = { withContext(NonCancellable) { release.await() } }
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Private input"))
            advanceTimeBy(250)
            runCurrent()
            vm.onEvent(ChatThreadEvent.Stopped)
            f.base.session.value = null
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals("", vm.state.value.draft)
            assertFalse(vm.state.value.draftReady)
            assertNotNull(vm.state.value.localFailure)
            assertTrue(effects.isEmpty())
        }
    }

    @Test
    fun slowAdmittedWriteCannotTrapBackNavigationAndLateCompletionCannotPublish() = runTest {
        withDraft { f, vm, store, effects ->
            val release = CompletableDeferred<Unit>()
            f.afterDraftWrite = { withContext(NonCancellable) { release.await() } }
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Slow write"))
            advanceTimeBy(250)
            runCurrent()
            vm.onEvent(ChatThreadEvent.BackRequested)
            advanceTimeBy(1_500)
            runCurrent()
            assertTrue(vm.state.value.draftSaving)
            assertEquals(ChatDraftDialog.LEAVE, vm.state.value.draftDialog)
            vm.onEvent(ChatThreadEvent.DraftDialogConfirmed)
            runCurrent()
            assertEquals(listOf<ChatThreadEffect>(ChatThreadEffect.Back), effects)
            store.clear()
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf<ChatThreadEffect>(ChatThreadEffect.Back), effects)
        }
    }

    private fun ChatThreadFixture.savedDraft(text: String) =
        CollaborationDraft(conversationId, chatId(86), text, base.clock.now())

    private suspend fun TestScope.withDraft(
        block:
            suspend TestScope.(
                ChatThreadFixture,
                ChatThreadViewModel,
                ViewModelStore,
                MutableList<ChatThreadEffect>,
            ) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ChatThreadFixture()
        val vm = f.model()
        val store = ViewModelStore().also { it.put("thread", vm) }
        val effects = mutableListOf<ChatThreadEffect>()
        val collection = backgroundScope.launch { vm.effects.collect { effects += it } }
        try {
            block(f, vm, store, effects)
        } finally {
            store.clear()
            collection.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
