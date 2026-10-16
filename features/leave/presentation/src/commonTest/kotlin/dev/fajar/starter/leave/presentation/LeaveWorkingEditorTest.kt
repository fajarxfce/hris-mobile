@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlin.uuid.ExperimentalUuidApi::class,
)

package dev.fajar.starter.leave.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.admitLeave
import dev.fajar.starter.leave.presentation.create.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*

class LeaveWorkingEditorTest {
    @Test
    fun unfinishedDatesPortionsReasonAndEvidenceReopenOfflineAfterCredentialExpiry() = runTest {
        var now = Instant.parse("2026-10-12T01:00:00Z")
        withEditor(LeaveCreateFixture { now }) { f, model, store ->
            selectType(f, model)
            model.onEvent(LeaveCreateEvent.DatePickerRequested(LeaveDateField.UNTIL))
            model.onEvent(LeaveCreateEvent.DateSelected(16))
            model.onEvent(LeaveCreateEvent.PortionPickerRequested)
            model.onEvent(LeaveCreateEvent.PortionSelected(LeavePortion.FIRST_HALF))
            model.onEvent(LeaveCreateEvent.ReasonChanged("Unfinished input"))
            model.onEvent(LeaveCreateEvent.EvidencePickerRequested)
            runCurrent()
            model.onEvent(
                LeaveCreateEvent.EvidenceSelected(
                    model.state.value.evidenceTarget!!,
                    leaveEvidence(),
                )
            )
            runCurrent()
            val input = model.state.value.form!!
            assertEquals(1, input.attachments.size)
            model.onEvent(LeaveCreateEvent.Stopped)
            runCurrent()
            assertEquals(input, f.working.stored?.input)
            val writes = f.working.writes
            store.clear()
            now += 3.days
            f.base.policyCall = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
            val restored = f.model()
            store.put("restored", restored)
            restored.onEvent(LeaveCreateEvent.Entered)
            runCurrent()
            assertEquals(input, restored.state.value.form)
            assertTrue(restored.state.value.editable)
            assertTrue(restored.state.value.persistence!!.saved)
            assertNull(restored.state.value.preview)
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
                model.onEvent(LeaveCreateEvent.ReasonChanged("First"))
                advanceTimeBy(251)
                runCurrent()
                assertEquals(1, f.working.active)
                repeat(50) { model.onEvent(LeaveCreateEvent.ReasonChanged("Latest $it")) }
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
            model.onEvent(LeaveCreateEvent.ReasonChanged("Committed without acknowledgement"))
            advanceTimeBy(251)
            runCurrent()
            val pending = model.state.value.persistence!!.pending!!
            assertEquals(pending.updated.revision, f.working.stored?.revision)
            assertEquals(FailureKind.Storage, model.state.value.persistence?.failure?.kind)
            model.onEvent(LeaveCreateEvent.ReasonChanged("Newer input"))
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(2, f.working.mutations.size)
            model.onEvent(LeaveCreateEvent.SaveRetryRequested)
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
            val effects = mutableListOf<LeaveCreateEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.effects.toList(effects)
            }
            val release = CompletableDeferred<Unit>()
            f.working.beforeSave = { release.await() }
            try {
                model.onEvent(LeaveCreateEvent.ReasonChanged("Keep on Back"))
                model.onEvent(LeaveCreateEvent.BackRequested)
                model.onEvent(LeaveCreateEvent.BackRequested)
                runCurrent()
                assertTrue(effects.isEmpty())
                assertEquals(LeaveCreateWork.LEAVING, model.state.value.work)
                assertFalse(model.state.value.editable)
                release.complete(Unit)
                runCurrent()
                assertEquals("Keep on Back", f.working.stored?.input?.reason)
                assertEquals(listOf<LeaveCreateEffect>(LeaveCreateEffect.Back), effects)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun failedSaveRequiresAnExplicitChoiceBeforeLeaving() = runTest {
        withEditor { f, model, _ ->
            val effects = mutableListOf<LeaveCreateEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.effects.toList(effects)
            }
            val previous = f.working.stored
            f.working.failure = Failure(FailureKind.Storage, "Unavailable")
            model.onEvent(LeaveCreateEvent.ReasonChanged("Unsaved input"))
            model.onEvent(LeaveCreateEvent.BackRequested)
            runCurrent()
            assertEquals(LeaveCreateDialog.LeaveUnsaved, model.state.value.dialog)
            assertTrue(effects.isEmpty())
            assertEquals("Unsaved input", model.state.value.form?.reason)
            model.onEvent(LeaveCreateEvent.ExitWithoutSaving)
            runCurrent()
            assertEquals(listOf<LeaveCreateEffect>(LeaveCreateEffect.Back), effects)
            assertEquals(previous, f.working.stored)
        }
    }

    @Test
    fun stopFlushesOnceAndDisposalRejectsLateSavePublication() = runTest {
        withEditor { f, model, store ->
            val release = CompletableDeferred<Unit>()
            f.working.beforeSave = { withContext(NonCancellable) { release.await() } }
            try {
                model.onEvent(LeaveCreateEvent.ReasonChanged("Pending at stop"))
                model.onEvent(LeaveCreateEvent.Stopped)
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
                model.onEvent(LeaveCreateEvent.ReasonChanged("Private input"))
                advanceTimeBy(251)
                runCurrent()
                val access =
                    (admitLeave(
                            f.base.workspace.id,
                            f.base.workspace,
                            f.base.session,
                            f.base.build,
                            f.base.clock.now(),
                        )
                            as AppResult.Success)
                        .value
                f.base.replica.restrict(
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
                f.base.policyCall = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
                model.onEvent(LeaveCreateEvent.ReloadRequested)
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
            model.onEvent(LeaveCreateEvent.DiscardDraftRequested)
            model.onEvent(LeaveCreateEvent.DiscardDraftConfirmed)
            runCurrent()
            assertEquals(newer, f.working.stored)
            assertEquals(
                "client.leave_working_copy_changed",
                model.state.value.persistence?.failure?.code,
            )
            assertFalse(model.state.value.editable)
            model.onEvent(LeaveCreateEvent.ReloadDraftRequested)
            model.onEvent(LeaveCreateEvent.ReloadDraftConfirmed)
            runCurrent()
            assertEquals("Another editor", model.state.value.form?.reason)
            assertTrue(model.state.value.persistence!!.saved)
        }
    }

    @Test
    fun confirmationWaitsForLocalDurabilityBeforeSendingTheOriginalInput() = runTest {
        withEditor { f, model, _ ->
            selectType(f, model)
            model.onEvent(LeaveCreateEvent.PreviewRequested)
            runCurrent()
            val release = CompletableDeferred<Unit>()
            f.working.beforeSave = { release.await() }
            try {
                model.onEvent(LeaveCreateEvent.ReasonChanged("Reviewed request"))
                model.onEvent(LeaveCreateEvent.SubmitRequested)
                runCurrent()
                assertTrue(f.transmitted.isEmpty())
                assertNull((f.records.value as AppResult.Success).value)
                model.onEvent(LeaveCreateEvent.ReasonChanged("Late change"))
                release.complete(Unit)
                runCurrent()
                assertEquals("Reviewed request", f.transmitted.single().reason)
                assertNotNull(f.transmitted.single().workingRevision)
                assertNull(f.working.stored)
            } finally {
                release.complete(Unit)
            }
        }
    }

    private fun TestScope.selectType(f: LeaveCreateFixture, model: LeaveCreateViewModel) {
        model.onEvent(LeaveCreateEvent.TypePickerRequested)
        model.onEvent(LeaveCreateEvent.TypeSelected(f.type.id))
        runCurrent()
    }

    private suspend fun TestScope.withEditor(
        fixture: LeaveCreateFixture = LeaveCreateFixture(),
        block: suspend TestScope.(LeaveCreateFixture, LeaveCreateViewModel, ViewModelStore) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val model = fixture.model()
            store.put("form", model)
            model.onEvent(LeaveCreateEvent.Entered)
            runCurrent()
            assertTrue(model.state.value.editable)
            block(fixture, model, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
