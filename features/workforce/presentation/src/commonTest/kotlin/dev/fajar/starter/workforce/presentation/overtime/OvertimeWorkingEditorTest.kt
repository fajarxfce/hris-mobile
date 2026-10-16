@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlin.uuid.ExperimentalUuidApi::class,
)

package dev.fajar.starter.workforce.presentation.overtime

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.presentation.overtime.plan.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*

class OvertimeWorkingEditorTest {
    @Test
    fun unfinishedTimesBreakAndReasonReopenOfflineAfterCredentialExpiry() = runTest {
        var now = Instant.parse("2026-10-12T13:00:00Z")
        withEditor(OvertimePlanFixture(StandardTestDispatcher(testScheduler)) { now }) {
            f,
            model,
            store ->
            model.onEvent(OvertimePlanEvent.TimePickerRequested(OvertimeTimeField.START))
            model.onEvent(OvertimePlanEvent.HoursChanged("18"))
            model.onEvent(OvertimePlanEvent.MinutesChanged("30"))
            model.onEvent(OvertimePlanEvent.TimeConfirmed)
            model.onEvent(OvertimePlanEvent.BreakChanged(""))
            model.onEvent(OvertimePlanEvent.ReasonChanged("Unfinished input"))
            runCurrent()
            val input = model.state.value.form!!
            assertEquals(kotlinx.datetime.LocalTime(18, 30), input.startsAt)
            assertNull(input.endsAt)
            model.onEvent(OvertimePlanEvent.Stopped)
            runCurrent()
            assertEquals(input, f.working.stored?.input)
            val writes = f.working.writes
            store.clear()
            now += 3.days
            f.read = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
            val restored = f.create(store)
            restored.onEvent(OvertimePlanEvent.Entered)
            runCurrent()
            assertEquals(input, restored.state.value.form)
            assertTrue(restored.state.value.editable)
            assertTrue(restored.state.value.persistence!!.saved)
            assertNull(restored.state.value.prepared)
            assertTrue(f.transmitted.isEmpty())
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
                model.onEvent(OvertimePlanEvent.ReasonChanged("First"))
                advanceTimeBy(251)
                runCurrent()
                assertEquals(1, f.working.active)
                repeat(50) { model.onEvent(OvertimePlanEvent.ReasonChanged("Latest $it")) }
                runCurrent()
                assertEquals(1, f.working.active)
                assertEquals(writes, f.working.writes)
                release.complete(Unit)
                runCurrent()
                assertEquals(writes + 2, f.working.writes)
                assertEquals(1, f.working.maxActive)
                assertEquals("Latest 49", f.working.stored?.input?.reason)
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
            model.onEvent(OvertimePlanEvent.ReasonChanged("Committed without acknowledgement"))
            advanceTimeBy(251)
            runCurrent()
            val pending = model.state.value.persistence!!.pending!!
            assertEquals(pending.updated.revision, f.working.stored?.revision)
            assertEquals(FailureKind.Storage, model.state.value.persistence?.failure?.kind)
            model.onEvent(OvertimePlanEvent.ReasonChanged("Newer input"))
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(2, f.working.mutations.size)
            model.onEvent(OvertimePlanEvent.SaveRetryRequested)
            runCurrent()
            assertEquals(pending.updated.revision, f.working.mutations[2].revision)
            assertEquals(pending.updated.input, f.working.mutations[2].input)
            assertEquals("Newer input", f.working.stored?.input?.reason)
            assertEquals(3, f.working.writes)
            assertTrue(model.state.value.persistence!!.saved)
            assertTrue(f.transmitted.isEmpty())
        }
    }

    @Test
    fun backWaitsForTheLastSaveAndDuplicateBackDoesNotCloseEarly() = runTest {
        withEditor { f, model, _ ->
            val effects = mutableListOf<OvertimePlanEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.effects.toList(effects)
            }
            val release = CompletableDeferred<Unit>()
            f.working.beforeSave = { release.await() }
            try {
                model.onEvent(OvertimePlanEvent.ReasonChanged("Keep on Back"))
                model.onEvent(OvertimePlanEvent.BackRequested)
                model.onEvent(OvertimePlanEvent.BackRequested)
                runCurrent()
                assertTrue(effects.isEmpty())
                assertEquals(OvertimePlanWork.LEAVING, model.state.value.work)
                assertFalse(model.state.value.editable)
                release.complete(Unit)
                runCurrent()
                assertEquals("Keep on Back", f.working.stored?.input?.reason)
                assertEquals(listOf<OvertimePlanEffect>(OvertimePlanEffect.Back), effects)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun failedSaveRequiresAnExplicitChoiceBeforeLeaving() = runTest {
        withEditor { f, model, _ ->
            val effects = mutableListOf<OvertimePlanEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.effects.toList(effects)
            }
            val previous = f.working.stored
            f.working.failure = Failure(FailureKind.Storage, "Unavailable")
            model.onEvent(OvertimePlanEvent.ReasonChanged("Unsaved input"))
            model.onEvent(OvertimePlanEvent.BackRequested)
            runCurrent()
            assertEquals(OvertimePlanDialog.LeaveUnsaved, model.state.value.dialog)
            assertTrue(effects.isEmpty())
            assertEquals("Unsaved input", model.state.value.form?.reason)
            model.onEvent(OvertimePlanEvent.ExitWithoutSaving)
            runCurrent()
            assertEquals(listOf<OvertimePlanEffect>(OvertimePlanEffect.Back), effects)
            assertEquals(previous, f.working.stored)
        }
    }

    @Test
    fun stopFlushesOnceAndDisposalRejectsLateSavePublication() = runTest {
        withEditor { f, model, store ->
            val release = CompletableDeferred<Unit>()
            f.working.beforeSave = { withContext(NonCancellable) { release.await() } }
            try {
                model.onEvent(OvertimePlanEvent.ReasonChanged("Pending at stop"))
                model.onEvent(OvertimePlanEvent.Stopped)
                runCurrent()
                assertEquals(1, f.working.active)
                val stopped = model.state.value
                store.clear()
                release.complete(Unit)
                runCurrent()
                assertEquals(stopped, model.state.value)
                assertEquals(0, f.working.active)
                assertEquals(0, f.observers)
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
                model.onEvent(OvertimePlanEvent.ReasonChanged("Private input"))
                advanceTimeBy(251)
                runCurrent()
                val access = f.base.access
                f.base.replicas.restrict(
                    access,
                    Failure(FailureKind.AccessDenied, "Verify", code = "mfa_required"),
                )
                runCurrent()
                assertNull(model.state.value.persistence)
                assertEquals("", model.state.value.form?.reason)
                release.complete(Unit)
                runCurrent()
                assertEquals("mfa_required", model.state.value.restriction?.code)
                assertNull(model.state.value.persistence)
                f.base.changesCall = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
                model.onEvent(OvertimePlanEvent.ReloadRequested)
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
            val original = requireNotNull(f.working.stored)
            val newer =
                original.copy(
                    revision = Uuid.random().toString(),
                    input = original.input.copy(reason = "Another editor"),
                )
            f.working.beforeRemove = { f.working.stored = newer }
            model.onEvent(OvertimePlanEvent.DiscardDraftRequested)
            model.onEvent(OvertimePlanEvent.DiscardDraftConfirmed)
            runCurrent()
            assertEquals(newer, f.working.stored)
            assertEquals(
                "client.overtime_working_copy_changed",
                model.state.value.persistence?.failure?.code,
            )
            assertFalse(model.state.value.editable)
            model.onEvent(OvertimePlanEvent.ReloadDraftRequested)
            model.onEvent(OvertimePlanEvent.ReloadDraftConfirmed)
            runCurrent()
            assertEquals("Another editor", model.state.value.form?.reason)
            assertTrue(model.state.value.persistence!!.saved)
        }
    }

    @Test
    fun confirmationWaitsForLocalDurabilityBeforeSendingTheOriginalInput() = runTest {
        withEditor { f, model, _ ->
            f.fill(model)
            runCurrent()
            val release = CompletableDeferred<Unit>()
            f.working.beforeSave = { release.await() }
            try {
                model.onEvent(OvertimePlanEvent.ReasonChanged("Reviewed request"))
                model.onEvent(OvertimePlanEvent.SubmitRequested)
                runCurrent()
                assertTrue(f.transmitted.isEmpty())
                assertNull((f.retained.value as AppResult.Success).value)
                model.onEvent(OvertimePlanEvent.ReasonChanged("Late change"))
                release.complete(Unit)
                runCurrent()
                assertEquals("Reviewed request", f.transmitted.single().reason)
                assertNotNull(
                    (f.transmitted.single().payload as OvertimeCommandPayload.Plan).workingRevision
                )
                assertNull(f.working.stored)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun aCompetingRevisionAtConfirmationOffersExplicitDraftReload() = runTest {
        withEditor { f, model, _ ->
            f.fill(model)
            advanceTimeBy(251)
            runCurrent()
            val previous = requireNotNull(f.working.stored)
            val newer =
                previous.copy(
                    revision = Uuid.random().toString(),
                    input = previous.input.copy(reason = "Another editor"),
                )
            f.working.stored = newer
            model.onEvent(OvertimePlanEvent.SubmitRequested)
            runCurrent()
            assertEquals(
                "client.overtime_working_copy_changed",
                model.state.value.persistence?.failure?.code,
            )
            assertFalse(model.state.value.editable)
            assertTrue(f.transmitted.isEmpty())
            model.onEvent(OvertimePlanEvent.ReloadDraftRequested)
            model.onEvent(OvertimePlanEvent.ReloadDraftConfirmed)
            runCurrent()
            assertEquals(newer.input, model.state.value.form)
            assertTrue(model.state.value.persistence!!.saved)
        }
    }

    private suspend fun TestScope.withEditor(
        fixture: OvertimePlanFixture? = null,
        block:
            suspend TestScope.(OvertimePlanFixture, OvertimePlanViewModel, ViewModelStore) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = fixture ?: OvertimePlanFixture(StandardTestDispatcher(testScheduler))
            val model = f.create(store)
            model.onEvent(OvertimePlanEvent.Entered)
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
