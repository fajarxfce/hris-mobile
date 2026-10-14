@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.fajar.starter.expenses.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.expenseFailure
import dev.fajar.starter.expenses.domain.working.entities.*
import dev.fajar.starter.expenses.presentation.draft.*
import dev.fajar.starter.expenses.presentation.item.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class ExpenseAutosaveTest {
    @Test
    fun aReloadWhoseLocalAcknowledgementWasLostRecoversTheReviewedVersion() = runTest {
        withDraft { f, _, owner ->
            val model = f.draftModel(f.draftTarget(true))
            owner.put("editing", model)
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseDraftEvent.TitleChanged("Local title"))
            model.onEvent(ExpenseDraftEvent.ReasonChanged("Correction"))
            runCurrent()
            f.currentClaim =
                f.currentClaim.copy(
                    version = 1,
                    draft = f.currentClaim.draft.copy(revision = 1, title = "Current server title"),
                )
            model.onEvent(ExpenseDraftEvent.SaveRequested)
            runCurrent()
            assertTrue(model.state.value.remoteChanged)
            f.copies.afterSave = { expenseFailure("fixture.storage", FailureKind.Storage) }
            model.onEvent(ExpenseDraftEvent.ReloadRequested)
            runCurrent()
            model.onEvent(ExpenseDraftEvent.ReloadConfirmed)
            runCurrent()
            assertEquals(FailureKind.Storage, model.state.value.persistence?.failure?.kind)
            val saved = f.copies.values.value.values.single()
            assertEquals(1L, saved.expectedVersion)
            val writes = f.copies.writes
            f.copies.afterSave = { null }
            model.onEvent(ExpenseDraftEvent.RetryLocalSave)
            runCurrent()
            assertTrue(model.state.value.persistence?.saved == true)
            assertEquals(writes, f.copies.writes)
            assertEquals(saved, model.state.value.persistence?.stored)
            assertEquals("Current server title", model.state.value.form?.title)
            assertEquals(1L, model.state.value.version)
            assertFalse(model.state.value.remoteChanged)
            assertTrue(model.state.value.editable)
            assertEquals(0, f.insertions)
        }
    }

    @Test
    fun aPendingLegacyCommandStaysGatedAndRefreshCannotCreateAnotherDraft() = runTest {
        withDraft { f, model, _ ->
            val record = assertIs<AppResult.Success<ExpenseCommandRecord>>(f.save()).value
            f.f.replicas.restrict(
                f.f.access,
                expenseFailure("mfa_required", FailureKind.AccessDenied).failure,
            )
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            assertTrue(model.state.value.needsVerification)
            assertFalse(model.state.value.commandReady)
            assertTrue(f.copies.values.value.isEmpty())
            model.onEvent(ExpenseDraftEvent.ReloadRequested)
            runCurrent()
            assertFalse(model.state.value.needsVerification)
            assertEquals(record, model.state.value.command)
            assertTrue(f.copies.values.value.isEmpty())
            model.onEvent(ExpenseDraftEvent.ReloadRequested)
            runCurrent()
            assertTrue(f.copies.values.value.isEmpty())
            assertEquals(1, f.insertions)
        }
    }

    @Test
    fun editingALegacyPendingCommandPersistsItsInputBeforeRemovingIt() = runTest {
        withDraft { f, _, owner ->
            val record =
                assertIs<AppResult.Success<ExpenseCommandRecord>>(f.save(edited = true)).value
            assertTrue(f.copies.values.value.isEmpty())
            val model = f.draftModel(f.draftTarget(true))
            owner.put("legacy", model)
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseDraftEvent.DiscardCommandRequested)
            runCurrent()
            model.onEvent(ExpenseDraftEvent.DiscardCommandConfirmed)
            runCurrent()
            assertTrue(f.values.value.isEmpty())
            val copy = f.copies.values.value.values.single()
            assertEquals(record.command.claimId, copy.claimId)
            assertEquals(record.command.reason, copy.input.reason)
            assertEquals(f.input, copy.input)
            assertTrue(model.state.value.editable)
        }
    }

    @Test
    fun failedLegacyInputPersistenceKeepsTheOriginalCommandRecoverable() = runTest {
        withDraft { f, model, _ ->
            val record = assertIs<AppResult.Success<ExpenseCommandRecord>>(f.save()).value
            f.copies.failure = expenseFailure("fixture.storage", FailureKind.Storage)
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseDraftEvent.DiscardCommandRequested)
            runCurrent()
            model.onEvent(ExpenseDraftEvent.DiscardCommandConfirmed)
            runCurrent()
            assertEquals(listOf(record), f.values.value)
            assertEquals(FailureKind.Storage, model.state.value.failure?.kind)
            assertFalse(model.state.value.editable)
        }
    }

    @Test
    fun incompleteItemAndDraftRestoreAfterProcessLossWithoutHttp() = runTest {
        withDraft { f, model, owner ->
            assertIs<AppResult.Success<*>>(f.f.load())
            f.f.directoryCall = { error("Editor must use owned local facts") }
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseDraftEvent.TitleChanged("Offline trip"))
            model.onEvent(ExpenseDraftEvent.ItemAdded)
            runCurrent()
            val target = assertNotNull(model.state.value.itemTarget)
            val child = f.itemModel(target)
            owner.put("item", child)
            child.onEvent(ExpenseItemEvent.Entered)
            runCurrent()
            child.onEvent(ExpenseItemEvent.AmountChanged("12,"))
            child.onEvent(ExpenseItemEvent.DescriptionChanged("Taxi, unfinished"))
            advanceTimeBy(300)
            runCurrent()
            assertTrue(child.state.value.persistence!!.saved)
            owner.clear()
            runCurrent()
            val restored = f.draftModel()
            owner.put("restored", restored)
            restored.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            assertEquals("Offline trip", restored.state.value.form?.title)
            assertEquals(target.copy.claimId, restored.state.value.persistence?.stored?.claimId)
            assertTrue(restored.state.value.editable)
            restored.onEvent(ExpenseDraftEvent.ItemResumed)
            runCurrent()
            val restoredChild = f.itemModel(assertNotNull(restored.state.value.itemTarget))
            owner.put("restored-item", restoredChild)
            restoredChild.onEvent(ExpenseItemEvent.Entered)
            runCurrent()
            assertEquals("12,", restoredChild.state.value.form.amount)
            assertEquals("Taxi, unfinished", restoredChild.state.value.form.description)
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test
    fun typingCoalescesAndBackFlushesWithoutCancellingAPendingWrite() = runTest {
        withDraft { f, model, _ ->
            val effects = mutableListOf<ExpenseDraftEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            val initialWrites = f.copies.writes
            repeat(50) { model.onEvent(ExpenseDraftEvent.TitleChanged("Title $it")) }
            runCurrent()
            assertEquals(initialWrites, f.copies.writes)
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var cancelled = false
            f.copies.beforeSave = {
                reached.complete(Unit)
                try {
                    release.await()
                } catch (error: CancellationException) {
                    cancelled = true
                    throw error
                }
            }
            advanceTimeBy(250)
            runCurrent()
            assertTrue(reached.isCompleted)
            model.onEvent(ExpenseDraftEvent.BackRequested)
            runCurrent()
            assertTrue(effects.isEmpty())
            assertFalse(cancelled)
            release.complete(Unit)
            runCurrent()
            assertEquals(initialWrites + 1, f.copies.writes)
            assertEquals("Title 49", f.copies.values.value.values.single().input.title)
            assertEquals(listOf<ExpenseDraftEffect>(ExpenseDraftEffect.Back), effects)
        }
    }

    @Test
    fun uncertainAcknowledgementIsRecoveredBeforeNewerKeystrokesAreStored() = runTest {
        withDraft { f, model, _ ->
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            val attempts = mutableListOf<ExpenseWorkingCopy>()
            f.copies.beforeSave = { attempts += it }
            f.copies.afterSave = { expenseFailure("fixture.storage", FailureKind.Storage) }
            model.onEvent(ExpenseDraftEvent.TitleChanged("First"))
            advanceTimeBy(300)
            runCurrent()
            assertNotNull(model.state.value.persistence?.failure)
            assertEquals("First", f.copies.values.value.values.single().input.title)
            model.onEvent(ExpenseDraftEvent.TitleChanged("Latest"))
            advanceTimeBy(300)
            runCurrent()
            assertEquals(1, attempts.size)
            f.copies.afterSave = { null }
            model.onEvent(ExpenseDraftEvent.RetryLocalSave)
            runCurrent()
            assertEquals(3, attempts.size)
            assertEquals(attempts[0].revision, attempts[1].revision)
            assertEquals(attempts[0].input, attempts[1].input)
            assertEquals("Latest", attempts[2].input.title)
            assertTrue(model.state.value.persistence!!.saved)
            assertEquals("Latest", f.copies.values.value.values.single().input.title)
        }
    }

    @Test
    fun anOlderAcknowledgementCannotClearAConflictOrOverwriteAnotherEditor() = runTest {
        withDraft { f, model, _ ->
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.copies.afterSave = {
                reached.complete(Unit)
                release.await()
                null
            }
            model.onEvent(ExpenseDraftEvent.TitleChanged("This editor"))
            advanceTimeBy(250)
            runCurrent()
            assertTrue(reached.isCompleted)
            val key = f.copies.values.value.keys.single()
            val newer =
                f.copies.values.value.values.single().let {
                    it.copy(
                        input = it.input.copy(title = "Another editor"),
                        revision = Uuid.random().toString(),
                    )
                }
            f.copies.values.value += key to newer
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(
                "client.expense_working_copy_changed",
                model.state.value.persistence?.failure?.code,
            )
            assertFalse(model.state.value.canSave)
            assertEquals(newer, f.copies.values.value.values.single())
            model.onEvent(ExpenseDraftEvent.ReloadRequested)
            runCurrent()
            assertEquals(ExpenseDraftDialog.RELOAD, model.state.value.dialog)
            model.onEvent(ExpenseDraftEvent.ReloadConfirmed)
            runCurrent()
            assertEquals("Another editor", model.state.value.form?.title)
        }
    }

    @Test
    fun denialCancelsPendingAutosaveAndLateCompletionCannotReopenInput() = runTest {
        withDraft { f, model, _ ->
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.copies.beforeSave = {
                reached.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            model.onEvent(ExpenseDraftEvent.TitleChanged("Pending input"))
            advanceTimeBy(250)
            runCurrent()
            assertTrue(reached.isCompleted)
            f.f.replicas.failAdmissionWrite = true
            f.f.replicas.restrict(
                f.f.access,
                expenseFailure("mfa_required", FailureKind.AccessDenied).failure,
            )
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertTrue(model.state.value.needsVerification)
            assertFalse(model.state.value.editable)
            assertEquals("", f.copies.values.value.values.single().input.title)
        }
    }

    @Test
    fun backExplainsAnUnpersistedChangeAndDoesNotSilentlyDiscardIt() = runTest {
        withDraft { f, model, _ ->
            val effects = mutableListOf<ExpenseDraftEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            f.copies.failure = expenseFailure("fixture.storage", FailureKind.Storage)
            model.onEvent(ExpenseDraftEvent.TitleChanged("Not yet stored"))
            model.onEvent(ExpenseDraftEvent.BackRequested)
            runCurrent()
            assertEquals(ExpenseDraftDialog.LEAVE_UNSAVED, model.state.value.dialog)
            assertTrue(effects.isEmpty())
            model.onEvent(ExpenseDraftEvent.ExitWithoutSaving)
            model.onEvent(ExpenseDraftEvent.ExitWithoutSaving)
            runCurrent()
            assertEquals(listOf<ExpenseDraftEffect>(ExpenseDraftEffect.Back), effects)
            assertEquals("", f.copies.values.value.values.single().input.title)
        }
    }

    private suspend fun TestScope.withDraft(
        block:
            suspend TestScope.(ExpenseEditorFixture, ExpenseDraftViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ExpenseEditorFixture {
            Instant.parse("2026-10-13T00:00:00Z") + testScheduler.currentTime.milliseconds
        }
        val model = fixture.draftModel()
        val owner = ViewModelStore().apply { put("draft", model) }
        try {
            block(fixture, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
