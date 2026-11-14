@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.recipient

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

class RecipientComposerViewModelTest {
    @Test
    fun reentryKeepsOneLocalReaderAndExpiredCredentialsDoNotHideOwnedDrafts() = runTest {
        withModel { f, model, _ ->
            model.onEvent(RecipientComposerEvent.TextChanged("Retain this draft"))
            advanceTimeBy(250)
            runCurrent()
            assertEquals("Retain this draft", f.saved.last().text)
            model.onEvent(RecipientComposerEvent.Stopped)
            val session = f.thread.base.initial
            f.thread.base.session.value =
                session.copy(tokens = session.tokens.copy(expiresAtEpochMillis = 0))
            model.onEvent(RecipientComposerEvent.Entered)
            runCurrent()
            assertEquals("Retain this draft", model.state.value.editor.text)
            assertEquals(1, f.starts)
            assertEquals(1, f.readers)
            assertEquals(0, f.thread.schedules)
        }
    }

    @Test
    fun typedEventsCaptureDistinctSendsWhileTheFirstLocalHandoffIsHeld() = runTest {
        withModel { f, model, _ ->
            val release = CompletableDeferred<Unit>()
            f.beforeHandoff = { release.await() }
            for (text in listOf("AAA", "BBB", "CCC")) {
                model.onEvent(RecipientComposerEvent.TextChanged(text))
                model.onEvent(RecipientComposerEvent.SendRequested)
                runCurrent()
                assertEquals("", model.state.value.editor.text)
            }
            model.onEvent(RecipientComposerEvent.TextChanged("Later typing"))
            runCurrent()
            val captured = model.state.value.editor.submissions.map { it.original }
            assertEquals(listOf("AAA", "BBB", "CCC"), captured.map { it.original.text })
            assertTrue(f.handed.isEmpty())
            release.complete(Unit)
            model.state.first {
                it.editor.submissions.size == 3 && it.editor.submissions.all { row -> row.durable }
            }
            runCurrent()
            assertEquals(captured, f.handed)
            assertEquals("Later typing", (f.facts.value as AppResult.Success).value?.text)
            assertEquals("Later typing", model.state.value.editor.text)
        }
    }

    @Test
    fun anUnconfirmedCommittedHandoffRetriesThroughTheUseCaseWithoutCreatingAnotherOriginal() =
        runTest {
            withModel { f, model, _ ->
                f.afterHandoff = { f.originalReadFailure = f.thread.storage }
                f.handoffFailure = f.thread.storage
                model.onEvent(RecipientComposerEvent.TextChanged("Original"))
                model.onEvent(RecipientComposerEvent.SendRequested)
                model.state.first { it.editor.failure != null }
                val original = f.handed.single()
                model.onEvent(RecipientComposerEvent.TextChanged("Later typing"))
                advanceTimeBy(86_400_000)
                runCurrent()
                assertEquals(listOf(original), f.handed)
                f.originalReadFailure = null
                f.handoffFailure = null
                f.afterHandoff = {}
                model.onEvent(RecipientComposerEvent.RetryRequested)
                model.state.first { it.editor.submissions.single().durable }
                runCurrent()
                assertEquals(listOf(original), f.handed)
                assertEquals("Later typing", (f.facts.value as AppResult.Success).value?.text)
            }
        }

    @Test
    fun readFailureKeepsTypedInputAndAReplayedSnapshotDoesNotOverwriteIt() = runTest {
        withModel { f, model, _ ->
            model.onEvent(RecipientComposerEvent.TextChanged("Unchanged input"))
            val retained = f.facts.value
            f.facts.value = AppResult.Failed(f.thread.storage)
            runCurrent()
            assertEquals("Unchanged input", model.state.value.editor.text)
            assertEquals(f.thread.storage, model.state.value.readFailure)
            f.facts.value = retained
            runCurrent()
            assertEquals("Unchanged input", model.state.value.editor.text)
            assertNull(model.state.value.readFailure)
        }
    }

    @Test
    fun aHiddenComposerRedactsOnDenialAndOnlyNewAdmissionRestoresItsOwnedDraft() = runTest {
        withModel { f, model, _ ->
            model.onEvent(RecipientComposerEvent.TextChanged("Private draft"))
            advanceTimeBy(250)
            runCurrent()
            model.onEvent(RecipientComposerEvent.Stopped)
            f.thread.gate.value =
                AppResult.Success(
                    CollaborationSpaceDirectoryAdmission(chatId(991), f.thread.denial)
                )
            runCurrent()
            assertEquals("", model.state.value.editor.text)
            assertEquals(f.thread.denial, model.state.value.restriction)
            assertEquals(0, f.readers)
            f.thread.gate.value = AppResult.Failed(f.thread.storage)
            runCurrent()
            assertFalse(model.state.value.editor.ready)
            f.thread.gate.value =
                AppResult.Success(CollaborationSpaceDirectoryAdmission(null, null))
            model.state.first { it.editor.ready }
            assertEquals("Private draft", model.state.value.editor.text)
            assertNull(model.state.value.restriction)
        }
    }

    @Test
    fun replacementAccountsCannotKeepThePreviousEditorOrItsReader() = runTest {
        withModel { f, model, _ ->
            model.onEvent(RecipientComposerEvent.TextChanged("Old account"))
            f.thread.base.session.value = null
            runCurrent()
            assertFalse(model.state.value.editor.ready)
            assertEquals("", model.state.value.editor.text)
            assertEquals(0, f.readers)
            model.onEvent(RecipientComposerEvent.TextChanged("Rejected"))
            model.onEvent(RecipientComposerEvent.SendRequested)
            runCurrent()
            assertTrue(f.handed.isEmpty())
        }
    }

    @Test
    fun disposingTheViewModelClosesReadersAndFencesALateLocalHandoff() = runTest {
        withModel { f, model, store ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeHandoff = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            model.onEvent(RecipientComposerEvent.TextChanged("Closing"))
            model.onEvent(RecipientComposerEvent.SendRequested)
            entered.await()
            try {
                store.clear()
                runCurrent()
                val retained = model.state.value
                assertEquals(0, f.readers)
                release.complete(Unit)
                runCurrent()
                assertEquals(retained, model.state.value)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun aWriteDenialCancelsTheOldReaderBeforeAnEarlierDraftCanReopenTheEditor() = runTest {
        withModel { f, model, _ ->
            val captured = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeDraftEmission = {
                withContext(NonCancellable) {
                    captured.complete(Unit)
                    release.await()
                }
            }
            f.afterHandoff = {
                captured.await()
                f.currentAdmission =
                    AppResult.Success(
                        CollaborationSpaceDirectoryAdmission(chatId(993), f.thread.denial)
                    )
            }
            try {
                model.onEvent(RecipientComposerEvent.TextChanged("Retained original"))
                model.onEvent(RecipientComposerEvent.SendRequested)
                model.state.first { it.restriction == f.thread.denial }
                assertFalse(model.state.value.editor.ready)
                // Current permission can recover before its observation publishes a new admission.
                // That does not authorize an already queued old draft read to reopen the screen.
                f.currentAdmission =
                    AppResult.Success(CollaborationSpaceDirectoryAdmission(null, null))
                release.complete(Unit)
                runCurrent()
                assertFalse(model.state.value.editor.ready)
                assertEquals(f.thread.denial, model.state.value.restriction)
                assertEquals(0, f.readers)
                f.beforeDraftEmission = {}
                f.afterHandoff = {}
                model.onEvent(RecipientComposerEvent.Entered)
                model.state.first { it.editor.ready }
                assertNull(model.state.value.restriction)
                assertEquals(2, f.starts)
                assertEquals(1, f.handed.size)
                assertEquals(f.handed.single(), f.originals.values.single())
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun repeatedReacquisitionWaitsForTheCancelledReaderToFinishBeforeOpeningAnother() = runTest {
        withModel { f, model, _ ->
            val captured = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeDraftEmission = {
                withContext(NonCancellable) {
                    captured.complete(Unit)
                    release.await()
                }
            }
            f.afterHandoff = {
                captured.await()
                f.currentAdmission =
                    AppResult.Success(
                        CollaborationSpaceDirectoryAdmission(chatId(994), f.thread.denial)
                    )
            }
            try {
                model.onEvent(RecipientComposerEvent.TextChanged("Original"))
                model.onEvent(RecipientComposerEvent.SendRequested)
                model.state.first { it.restriction == f.thread.denial }
                f.currentAdmission =
                    AppResult.Success(CollaborationSpaceDirectoryAdmission(null, null))
                repeat(3) {
                    model.onEvent(RecipientComposerEvent.Entered)
                    model.onEvent(RecipientComposerEvent.RetryRequested)
                    runCurrent()
                }
                assertFalse(model.state.value.editor.ready)
                assertEquals(1, f.starts)
                assertEquals(1, f.maximumReaders)
                f.beforeDraftEmission = {}
                release.complete(Unit)
                model.state.first { it.editor.ready }
                assertEquals(2, f.starts)
                assertEquals(1, f.maximumReaders)
                assertEquals(1, f.readers)
            } finally {
                release.complete(Unit)
            }
        }
    }

    private suspend fun TestScope.withModel(
        block:
            suspend TestScope.(
                RecipientComposerViewModelFixture, RecipientComposerViewModel, ViewModelStore,
            ) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = RecipientComposerViewModelFixture()
        val model = f.model()
        val store = ViewModelStore().also { it.put("recipient", model) }
        try {
            assertEquals(0, f.starts)
            model.onEvent(RecipientComposerEvent.Entered)
            model.state.first { it.editor.ready }
            block(f, model, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
