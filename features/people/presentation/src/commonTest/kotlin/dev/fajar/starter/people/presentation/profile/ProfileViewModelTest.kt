@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.people.presentation.profile

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.profile.entities.*
import dev.fajar.starter.people.domain.profile.policies.*
import dev.fajar.starter.sync.domain.entities.SyncChangePage
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class ProfileViewModelTest {
    @Test
    fun duplicateEntryIsDroppedAndStoppingCancelsAcquisitionWhileWatchingAccess() = runTest {
        withModel { f, model, owner ->
            model.onEvent(ProfileEvent.Entered)
            model.onEvent(ProfileEvent.Entered)
            runCurrent()
            assertEquals(2, f.reads.size)
            assertEquals(1, f.store.observers)
            var cancelled = false
            f.changesCall = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            model.onEvent(ProfileEvent.RefreshRequested)
            model.onEvent(ProfileEvent.RefreshRequested)
            runCurrent()
            assertEquals(2, f.changeReads)
            assertNotNull(model.state.value.view)
            model.onEvent(ProfileEvent.Stopped)
            runCurrent()
            assertTrue(cancelled)
            assertFalse(model.state.value.syncing)
            assertNotNull(model.state.value.view)
            assertEquals(1, f.store.observers)
            f.base.workspaces.state.value = AppResult.Success(f.workspace.copy(id = "replacement"))
            runCurrent()
            assertNull(model.state.value.view)
            assertTrue(model.state.value.readRestricted)
            owner.clear()
            runCurrent()
            assertEquals(0, f.store.observers)
        }
    }

    @Test
    fun selectionQueriesLocalFactsWhileAnExplicitRefreshIsPending() = runTest {
        withModel { f, model, _ ->
            model.onEvent(ProfileEvent.Entered)
            runCurrent()
            val release = CompletableDeferred<Unit>()
            f.changesCall = {
                release.await()
                AppResult.Success(SyncChangePage(emptyList(), "head", false, false, 5, f.now))
            }
            model.onEvent(ProfileEvent.RefreshRequested)
            runCurrent()
            model.onEvent(ProfileEvent.EmploymentRequested)
            runCurrent()
            assertTrue(model.state.value.choosingEmployment)
            model.onEvent(ProfileEvent.EmploymentSelected("foreign"))
            runCurrent()
            assertEquals(selfProfileId(1), model.state.value.selectedId)
            model.onEvent(ProfileEvent.EmploymentSelected(selfProfileId(2)))
            runCurrent()
            assertEquals(selfProfileId(2), model.state.value.view?.profile?.details?.employee?.id)
            assertFalse(model.state.value.choosingEmployment)
            assertTrue(model.state.value.syncing)
            release.complete(Unit)
            runCurrent()
            assertEquals(selfProfileId(2), model.state.value.selectedId)
            assertEquals(2, f.reads.size)
            assertEquals(1, f.bootstrapReads)
            assertEquals(2, f.changeReads)
        }
    }

    @Test
    fun knownDenialSurvivesFailedCleanupPendingRetryAndTransportFailure() = runTest {
        withModel { f, model, _ ->
            model.onEvent(ProfileEvent.Entered)
            runCurrent()
            f.store.failedClear = true
            f.policyCall = { selfProfileFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(ProfileEvent.RefreshRequested)
            runCurrent()
            assertNotNull((f.store.snapshot.value as AppResult.Success).value.checkpoint)
            assertTrue(model.state.value.needsVerification)
            assertNull(model.state.value.view)
            assertEquals(0, f.store.observers)
            val effects = mutableListOf<ProfileEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(ProfileEvent.VerificationRequested)
            runCurrent()
            assertEquals(listOf<ProfileEffect>(ProfileEffect.VerifyAccount), effects)
            val release = CompletableDeferred<Unit>()
            f.policyCall = {
                release.await()
                selfProfileFailure("fixture.offline", FailureKind.Network)
            }
            model.onEvent(ProfileEvent.RefreshRequested)
            runCurrent()
            model.onEvent(ProfileEvent.EmploymentRequested)
            runCurrent()
            assertFalse(model.state.value.choosingEmployment)
            assertTrue(model.state.value.readRestricted)
            assertNull(model.state.value.view)
            release.complete(Unit)
            runCurrent()
            assertEquals("mfa_required", model.state.value.failure?.code)
            f.policyCall = { AppResult.Success(f.workspace.policy!!.policy) }
            model.onEvent(ProfileEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.view)
            assertNull(model.state.value.failure)
            assertEquals(1, f.store.observers)
            assertFalse(model.state.value.readRestricted)
        }
    }

    @Test
    fun admittedRefreshReopensACompletedStorageSourceEvenWithAnActiveOuterCollector() = runTest {
        withModel { f, model, _ ->
            f.store.failNextObservation = true
            val release = CompletableDeferred<Unit>()
            f.recordCall = { id ->
                release.await()
                AppResult.Success(f.rows.getValue(id))
            }
            model.onEvent(ProfileEvent.Entered)
            runCurrent()
            assertEquals(FailureKind.Storage, model.state.value.failure?.kind)
            assertEquals(0, f.store.observers)
            release.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.view)
            assertNull(model.state.value.failure)
            assertEquals(1, f.store.observers)
            assertEquals(2, f.reads.size)
        }
    }

    @Test
    fun ordinaryAgeAndFailedRefreshKeepFactsWithoutAnAutomaticRetryLoop() = runTest {
        withModel { f, model, _ ->
            model.onEvent(ProfileEvent.Entered)
            runCurrent()
            val view = model.state.value.view
            advanceTimeBy(73 * 60 * 60_000L)
            runCurrent()
            assertEquals(view, model.state.value.view)
            assertEquals(1, f.changeReads)
            f.changesCall = { selfProfileFailure("fixture.offline", FailureKind.Network) }
            model.onEvent(ProfileEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.view)
            assertEquals("fixture.offline", model.state.value.failure?.code)
            assertFalse(model.state.value.loading)
            assertFalse(model.state.value.readRestricted)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(2, f.changeReads)
            f.changesCall = {
                AppResult.Success(SyncChangePage(emptyList(), "head", false, false, 5, f.now))
            }
            model.onEvent(ProfileEvent.RefreshRequested)
            runCurrent()
            assertNull(model.state.value.failure)
        }
    }

    @Test
    fun disposedOwnerCannotPublishALateNativeResult() = runTest {
        withModel { f, model, owner ->
            val release = CompletableDeferred<Unit>()
            f.recordCall = { id ->
                withContext(NonCancellable) { release.await() }
                AppResult.Success(f.rows.getValue(id))
            }
            model.onEvent(ProfileEvent.Entered)
            runCurrent()
            assertEquals(2, f.reads.size)
            owner.clear()
            release.complete(Unit)
            runCurrent()
            assertNull(model.state.value.view)
            assertNull((f.store.snapshot.value as AppResult.Success).value.checkpoint)
            assertEquals(0, f.store.observers)
        }
    }

    @Test
    fun queuedOldSelectionAndLocalEmissionCannotOverwriteCurrentSelectionOrDenial() {
        val f = ProfileFixture()
        val record = selfProfileRecord(1)
        val view =
            SelfProfileView(
                listOf(record.profile.details.employee),
                record.id,
                record.profile,
                f.now,
                record.id,
            )
        val selecting = ProfileState(selectedId = selfProfileId(2))
        assertEquals(selecting, selecting.withProfileView(AppResult.Success(view)))
        val denied =
            ProfileState(
                selectedId = record.id,
                readRestricted = true,
                cacheFailure =
                    Failure(FailureKind.AccessDenied, "Restricted", code = "mfa_required"),
            )
        assertEquals(denied, denied.withProfileView(AppResult.Success(view)))
        val pending = denied.withProfileSync(AppResult.Success(SelfProfileSyncOutcome(false, 5)))
        assertTrue(pending.readRestricted)
        assertTrue(pending.needsVerification)
    }

    private suspend fun TestScope.withModel(
        test: suspend (ProfileFixture, ProfileViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ProfileFixture()
        val model = ProfileViewModel("workspace", f.observe, f.sync)
        val owner = ViewModelStore().apply { put("profile", model) }
        try {
            test(f, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            assertEquals(0, f.store.observers)
            assertEquals(0, f.store.admissionObservers)
            Dispatchers.resetMain()
        }
    }
}
