@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlin.uuid.ExperimentalUuidApi::class,
)

package dev.fajar.starter.workforce.presentation.overtime

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.presentation.overtime.action.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*

class OvertimeActionWorkingEditorTest {
    @Test
    fun actualInputAndIncompleteBreakReopenOfflineAfterCredentialExpiry() = runTest {
        var now = Instant.parse("2026-10-12T13:00:00Z")
        withEditor(OvertimeActionFixture(StandardTestDispatcher(testScheduler)) { now }) {
            f,
            model,
            store ->
            model.onEvent(OvertimeActionEvent.TimePickerRequested(OvertimeActualField.START))
            model.onEvent(OvertimeActionEvent.HoursChanged("18"))
            model.onEvent(OvertimeActionEvent.MinutesChanged("30"))
            model.onEvent(OvertimeActionEvent.TimeConfirmed)
            model.onEvent(OvertimeActionEvent.BreakChanged(""))
            model.onEvent(OvertimeActionEvent.ReasonChanged("Unfinished input"))
            runCurrent()
            val input = model.state.value.form!!
            assertEquals(
                kotlinx.datetime.LocalDateTime(2026, 10, 12, 18, 30),
                input.actual?.startsAt,
            )
            assertNotNull(input.actual?.endsAt)
            model.onEvent(OvertimeActionEvent.Stopped)
            runCurrent()
            assertEquals(input, f.working.stored(f.kind.action)?.input)
            val writes = f.working.writes
            store.clear()
            now += 3.days
            f.base.detailsCall = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
            val restored = f.create(store)
            restored.onEvent(OvertimeActionEvent.Entered)
            runCurrent()
            assertEquals(input, restored.state.value.form)
            assertTrue(restored.state.value.editable)
            assertTrue(restored.state.value.persistence!!.saved)
            assertNotNull(restored.state.value.content)
            assertTrue(f.persistence.transmitted.isEmpty())
            assertEquals(writes, f.working.writes)
            assertEquals(0, f.working.observations)
        }
    }

    @Test
    fun pendingAutosaveCoalescesNewInputWithoutCancellingTheAdmittedWrite() = runTest {
        withEditor { f, model, _ ->
            val release = CompletableDeferred<Unit>()
            val writes = f.working.writes
            f.working.beforeSave = { release.await() }
            try {
                model.onEvent(OvertimeActionEvent.ReasonChanged("First"))
                advanceTimeBy(251)
                runCurrent()
                assertEquals(1, f.working.active)
                repeat(50) { model.onEvent(OvertimeActionEvent.ReasonChanged("Latest $it")) }
                runCurrent()
                assertEquals(1, f.working.active)
                assertEquals(writes, f.working.writes)
                release.complete(Unit)
                runCurrent()
                assertEquals(writes + 2, f.working.writes)
                assertEquals(1, f.working.maxActive)
                assertEquals("Latest 49", f.working.stored(f.kind.action)?.input?.reason)
                assertTrue(model.state.value.persistence!!.saved)
                advanceTimeBy(10_000)
                runCurrent()
                assertEquals(writes + 2, f.working.writes)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun unknownLocalCommitRetriesTheSameMutationBeforeSavingNewerInput() = runTest {
        withEditor { f, model, _ ->
            f.working.loseAcknowledgement = true
            model.onEvent(OvertimeActionEvent.ReasonChanged("Committed without acknowledgement"))
            advanceTimeBy(251)
            runCurrent()
            val pending = model.state.value.persistence!!.pending!!
            assertEquals(pending.updated.revision, f.working.stored(f.kind.action)?.revision)
            assertEquals(FailureKind.Storage, model.state.value.persistence?.failure?.kind)
            model.onEvent(OvertimeActionEvent.ReasonChanged("Newer input"))
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(2, f.working.mutations.size)
            model.onEvent(OvertimeActionEvent.SaveRetryRequested)
            runCurrent()
            assertEquals(pending.updated.revision, f.working.mutations[2].revision)
            assertEquals(pending.updated.input, f.working.mutations[2].input)
            assertEquals("Newer input", f.working.stored(f.kind.action)?.input?.reason)
            assertEquals(3, f.working.writes)
            assertTrue(model.state.value.persistence!!.saved)
            assertTrue(f.persistence.transmitted.isEmpty())
        }
    }

    @Test
    fun backWaitsForTheLastSaveAndDuplicateBackDoesNotCloseEarly() = runTest {
        withEditor { f, model, _ ->
            val effects = mutableListOf<OvertimeActionEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.effects.toList(effects)
            }
            val release = CompletableDeferred<Unit>()
            f.working.beforeSave = { release.await() }
            try {
                model.onEvent(OvertimeActionEvent.ReasonChanged("Keep on Back"))
                model.onEvent(OvertimeActionEvent.BackRequested)
                model.onEvent(OvertimeActionEvent.BackRequested)
                runCurrent()
                assertTrue(effects.isEmpty())
                assertEquals(OvertimeActionWork.LEAVING, model.state.value.work)
                assertFalse(model.state.value.editable)
                release.complete(Unit)
                runCurrent()
                assertEquals("Keep on Back", f.working.stored(f.kind.action)?.input?.reason)
                assertEquals(listOf<OvertimeActionEffect>(OvertimeActionEffect.Back), effects)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun failedSaveRequiresAnExplicitChoiceBeforeLeaving() = runTest {
        withEditor { f, model, _ ->
            val effects = mutableListOf<OvertimeActionEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.effects.toList(effects)
            }
            val previous = f.working.stored(f.kind.action)
            f.working.failure = Failure(FailureKind.Storage, "Unavailable")
            model.onEvent(OvertimeActionEvent.ReasonChanged("Unsaved input"))
            model.onEvent(OvertimeActionEvent.BackRequested)
            runCurrent()
            assertEquals(OvertimeActionDialog.LeaveUnsaved, model.state.value.dialog)
            assertTrue(effects.isEmpty())
            assertEquals("Unsaved input", model.state.value.form?.reason)
            model.onEvent(OvertimeActionEvent.ExitWithoutSaving)
            runCurrent()
            assertEquals(listOf<OvertimeActionEffect>(OvertimeActionEffect.Back), effects)
            assertEquals(previous, f.working.stored(f.kind.action))
        }
    }

    @Test
    fun stopFlushesOnceAndDisposalRejectsLateSavePublication() = runTest {
        withEditor { f, model, store ->
            val release = CompletableDeferred<Unit>()
            f.working.beforeSave = { withContext(NonCancellable) { release.await() } }
            try {
                model.onEvent(OvertimeActionEvent.ReasonChanged("Pending at stop"))
                model.onEvent(OvertimeActionEvent.Stopped)
                runCurrent()
                assertEquals(1, f.working.active)
                val stopped = model.state.value
                store.clear()
                release.complete(Unit)
                runCurrent()
                assertEquals(stopped, model.state.value)
                assertEquals(0, f.working.active)
                assertEquals(0, f.persistence.observers)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun anAuthoritativeDenialHidesInputWhileAnOldSaveFinishes() = runTest {
        withEditor { f, model, _ ->
            val release = CompletableDeferred<Unit>()
            f.working.beforeSave = { withContext(NonCancellable) { release.await() } }
            try {
                model.onEvent(OvertimeActionEvent.ReasonChanged("Private input"))
                advanceTimeBy(251)
                runCurrent()
                val access = f.base.access
                f.base.replicas.restrict(
                    access,
                    Failure(FailureKind.AccessDenied, "Verify", code = "mfa_required"),
                )
                runCurrent()
                assertNull(model.state.value.persistence)
                assertNull(model.state.value.form)
                release.complete(Unit)
                runCurrent()
                assertEquals("mfa_required", model.state.value.restriction?.code)
                assertNull(model.state.value.persistence)
                f.base.changesCall = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
                model.onEvent(OvertimeActionEvent.RefreshRequested)
                runCurrent()
                assertEquals("mfa_required", model.state.value.restriction?.code)
                assertFalse(model.state.value.editable)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun discardCannotRemoveACompetingEditAndReloadShowsThatSavedVersion() = runTest {
        withEditor { f, model, _ ->
            val original = requireNotNull(f.working.stored(f.kind.action))
            val newer =
                original.copy(
                    revision = Uuid.random().toString(),
                    input = original.input.copy(reason = "Another editor"),
                )
            f.working.beforeRemove = { f.working.records[newer.requestId to newer.action] = newer }
            model.onEvent(OvertimeActionEvent.DiscardDraftRequested)
            model.onEvent(OvertimeActionEvent.DiscardDraftConfirmed)
            runCurrent()
            assertEquals(newer, f.working.stored(f.kind.action))
            assertEquals(
                "client.overtime_working_copy_changed",
                model.state.value.persistence?.failure?.code,
            )
            assertFalse(model.state.value.editable)
            model.onEvent(OvertimeActionEvent.ReloadDraftRequested)
            model.onEvent(OvertimeActionEvent.ReloadDraftConfirmed)
            runCurrent()
            assertEquals("Another editor", model.state.value.form?.reason)
            assertTrue(model.state.value.persistence!!.saved)
        }
    }

    @Test
    fun confirmationWaitsForLocalDurabilityBeforeSendingTheOriginalInput() = runTest {
        withEditor { f, model, _ ->
            runCurrent()
            val release = CompletableDeferred<Unit>()
            f.working.beforeSave = { release.await() }
            try {
                model.onEvent(OvertimeActionEvent.ReasonChanged("Reviewed request"))
                model.onEvent(OvertimeActionEvent.SubmitRequested)
                runCurrent()
                assertTrue(f.persistence.transmitted.isEmpty())
                assertNull((f.persistence.retained.value as AppResult.Success).value)
                model.onEvent(OvertimeActionEvent.ReasonChanged("Late change"))
                release.complete(Unit)
                runCurrent()
                assertEquals("Reviewed request", f.persistence.transmitted.single().reason)
                assertNotNull(f.persistence.transmitted.single().payload.workingRevision)
                assertNull(f.working.stored(f.kind.action))
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun actualAndWithdrawalDraftsRestoreIndependentlyOnTheSameRequest() = runTest {
        withEditor { f, model, store ->
            model.onEvent(OvertimeActionEvent.ReasonChanged("Actual evidence"))
            model.onEvent(OvertimeActionEvent.Stopped)
            runCurrent()
            store.clear()
            val withdrawal = f.create(store, OvertimeActionKind.WITHDRAW)
            withdrawal.onEvent(OvertimeActionEvent.Entered)
            runCurrent()
            assertNull(withdrawal.state.value.form?.actual)
            withdrawal.onEvent(OvertimeActionEvent.ReasonChanged("Withdrawal draft"))
            withdrawal.onEvent(OvertimeActionEvent.Stopped)
            runCurrent()
            store.clear()
            f.base.detailsCall = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
            val restored = f.create(store)
            restored.onEvent(OvertimeActionEvent.Entered)
            runCurrent()
            assertEquals("Actual evidence", restored.state.value.form?.reason)
            assertEquals(
                "Withdrawal draft",
                f.working.stored(OvertimeAction.WITHDRAW)?.input?.reason,
            )
            assertEquals(2, f.working.records.size)
            assertTrue(f.persistence.transmitted.isEmpty())
        }
    }

    @Test
    fun acquiredInputRemainsEditableWhileFreshRequestAcquisitionIsPending() = runTest {
        withEditor { f, model, _ ->
            val release = CompletableDeferred<Unit>()
            f.base.detailsCall = {
                release.await()
                AppResult.Success(f.latest)
            }
            try {
                model.onEvent(OvertimeActionEvent.RefreshRequested)
                runCurrent()
                assertEquals(OvertimeActionWork.REFRESH, model.state.value.work)
                assertTrue(model.state.value.editable)
                model.onEvent(OvertimeActionEvent.ReasonChanged("Edited during refresh"))
                advanceTimeBy(251)
                runCurrent()
                assertEquals(
                    "Edited during refresh",
                    f.working.stored(f.kind.action)?.input?.reason,
                )
                release.complete(Unit)
                runCurrent()
                assertEquals("Edited during refresh", model.state.value.form?.reason)
                assertTrue(model.state.value.persistence!!.saved)
                assertTrue(f.persistence.transmitted.isEmpty())
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun aCompetingRevisionAtSubmissionOffersExplicitDraftReload() = runTest {
        withEditor { f, model, _ ->
            model.onEvent(OvertimeActionEvent.ReasonChanged("This screen"))
            advanceTimeBy(251)
            runCurrent()
            val previous = requireNotNull(f.working.stored(f.kind.action))
            val newer =
                previous.copy(
                    revision = Uuid.random().toString(),
                    input = previous.input.copy(reason = "Another screen"),
                )
            f.working.records[newer.requestId to newer.action] = newer
            model.onEvent(OvertimeActionEvent.SubmitRequested)
            runCurrent()
            assertEquals(
                "client.overtime_working_copy_changed",
                model.state.value.persistence?.failure?.code,
            )
            assertFalse(model.state.value.editable)
            assertTrue(f.persistence.transmitted.isEmpty())
            model.onEvent(OvertimeActionEvent.ReloadDraftRequested)
            model.onEvent(OvertimeActionEvent.ReloadDraftConfirmed)
            runCurrent()
            assertEquals(newer.input, model.state.value.form)
            assertTrue(model.state.value.persistence!!.saved)
        }
    }

    private suspend fun TestScope.withEditor(
        fixture: OvertimeActionFixture? = null,
        block:
            suspend TestScope.(
                OvertimeActionFixture, OvertimeActionViewModel, ViewModelStore,
            ) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = fixture ?: OvertimeActionFixture(StandardTestDispatcher(testScheduler))
            val model = f.create(store)
            model.onEvent(OvertimeActionEvent.Entered)
            runCurrent()
            assertTrue(model.state.value.editable)
            block(f, model, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
