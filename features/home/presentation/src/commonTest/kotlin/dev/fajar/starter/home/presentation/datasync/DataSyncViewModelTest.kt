@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.home.presentation.datasync

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.CompanyDataStatus
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class DataSyncViewModelTest {
    @Test
    fun entryAndReturnObserveSavedMetadataWithoutStartingAcquisition() = runTest {
        withModel { f, model, owner ->
            model.onEvent(DataSyncEvent.Entered)
            model.onEvent(DataSyncEvent.Entered)
            runCurrent()
            assertEquals(f.workspace.company.name, model.state.value.companyName)
            assertTrue(model.state.value.rows.all { it.status?.lastCompletedAt == f.now })
            assertTrue(f.observations.all { it.active == 1 && it.scopes == listOf(f.workspace.id) })
            assertTrue(f.requests.all { it.scopes.isEmpty() })
            model.onEvent(DataSyncEvent.Stopped)
            model.onEvent(DataSyncEvent.Entered)
            runCurrent()
            assertTrue(f.observations.all { it.scopes.size == 1 })
            assertTrue(f.requests.all { it.scopes.isEmpty() })
            owner.clear()
            runCurrent()
            assertTrue(f.observations.all { it.active == 0 })
        }
    }

    @Test
    fun allRefreshesAreBoundedAndDuplicateInputDoesNotResendCompletedOrPendingWork() = runTest {
        withModel { f, model, _ ->
            model.onEvent(DataSyncEvent.Entered)
            model.onEvent(DataSyncEvent.RefreshRequested)
            model.onEvent(DataSyncEvent.RefreshRequested)
            runCurrent()
            assertEquals(3, f.active)
            assertEquals(
                3,
                model.state.value.rows.count { it.progress == DataSyncProgress.Running },
            )
            assertEquals(6, model.state.value.rows.count { it.progress == DataSyncProgress.Queued })
            model.onEvent(DataSyncEvent.DatasetRefreshRequested(CompanyDataSet.Profile))
            runCurrent()
            assertEquals(1, f.request(CompanyDataSet.Profile).scopes.size)
            f.request(CompanyDataSet.Profile)
                .response
                .complete(AppResult.Success(TestSyncOutcome()))
            runCurrent()
            assertEquals(3, f.active)
            model.onEvent(DataSyncEvent.RefreshRequested)
            runCurrent()
            assertEquals(1, f.request(CompanyDataSet.Profile).scopes.size)
            f.requests.forEach { it.response.complete(AppResult.Success(TestSyncOutcome())) }
            runCurrent()
            assertEquals(3, f.peak)
            assertEquals(0, f.active)
            assertTrue(f.requests.all { it.scopes == listOf(f.workspace.id) })
            assertFalse(model.state.value.syncing)
            assertTrue(model.state.value.rows.all { it.status?.lastCompletedAt == f.now })
        }
    }

    @Test
    fun aFailedDatasetDoesNotCancelOtherAcquisitionsAndPartialWorkDoesNotLoop() = runTest {
        withModel { f, model, _ ->
            val offline = Failure(FailureKind.Network, "Connection unavailable")
            f.request(CompanyDataSet.Inbox).response.complete(AppResult.Failed(offline))
            f.request(CompanyDataSet.Payroll)
                .response
                .complete(AppResult.Success(TestSyncOutcome(false)))
            f.requests.forEach { it.response.complete(AppResult.Success(TestSyncOutcome())) }
            model.onEvent(DataSyncEvent.Entered)
            model.onEvent(DataSyncEvent.RefreshRequested)
            runCurrent()
            assertEquals(offline, model.row(CompanyDataSet.Inbox).syncFailure)
            assertEquals(f.now, model.row(CompanyDataSet.Inbox).status?.lastCompletedAt)
            assertEquals(DataSyncProgress.Incomplete, model.row(CompanyDataSet.Payroll).progress)
            assertFalse(model.state.value.syncing)
            assertTrue(f.requests.all { it.scopes.size == 1 })
            f.observation(CompanyDataSet.Payroll).values.value =
                AppResult.Success(CompanyDataStatus(null))
            advanceTimeBy(24.hours)
            runCurrent()
            assertTrue(f.requests.all { it.scopes.size == 1 })
        }
    }

    @Test
    fun metadataChangesOnlyWhenTheLocalSourcePublishesAndOtherRowsKeepTheirIdentity() = runTest {
        withModel { f, model, _ ->
            model.onEvent(DataSyncEvent.Entered)
            runCurrent()
            val other = model.row(CompanyDataSet.Employees)
            f.request(CompanyDataSet.Inbox).response.complete(AppResult.Success(TestSyncOutcome()))
            model.onEvent(DataSyncEvent.DatasetRefreshRequested(CompanyDataSet.Inbox))
            runCurrent()
            assertEquals(f.now, model.row(CompanyDataSet.Inbox).status?.lastCompletedAt)
            f.observation(CompanyDataSet.Inbox).values.value =
                AppResult.Success(CompanyDataStatus(f.now + 1.hours))
            runCurrent()
            assertEquals(f.now + 1.hours, model.row(CompanyDataSet.Inbox).status?.lastCompletedAt)
            assertSame(other, model.row(CompanyDataSet.Employees))
        }
    }

    @Test
    fun readDenialRemainsVisibleThroughoutPendingAndFailedRetry() = runTest {
        withModel { f, model, _ ->
            val denial =
                Failure(FailureKind.AccessDenied, "Verification required", code = "mfa_required")
            model.onEvent(DataSyncEvent.Entered)
            runCurrent()
            f.observation(CompanyDataSet.Inbox).values.value = AppResult.Failed(denial)
            runCurrent()
            model.onEvent(DataSyncEvent.DatasetRefreshRequested(CompanyDataSet.Inbox))
            runCurrent()
            assertNull(model.row(CompanyDataSet.Inbox).status)
            assertEquals(denial, model.row(CompanyDataSet.Inbox).failure)
            f.request(CompanyDataSet.Inbox)
                .response
                .complete(AppResult.Failed(Failure(FailureKind.Network, "Offline")))
            runCurrent()
            assertNull(model.row(CompanyDataSet.Inbox).status)
            assertEquals(denial, model.row(CompanyDataSet.Inbox).failure)
            assertTrue(model.state.value.needsVerification)
            val effects = mutableListOf<DataSyncEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(DataSyncEvent.VerificationRequested)
            runCurrent()
            assertEquals(listOf<DataSyncEffect>(DataSyncEffect.VerifyAccount), effects)
        }
    }

    @Test
    fun completedStorageFailureCanRecoverAfterAnAdmittedRefreshWithoutLeavingThePage() = runTest {
        withModel { f, model, _ ->
            val source = f.observation(CompanyDataSet.Inbox)
            source.read = { flowOf(AppResult.Failed(Failure(FailureKind.Storage, "Unavailable"))) }
            model.onEvent(DataSyncEvent.Entered)
            runCurrent()
            assertEquals(FailureKind.Storage, model.row(CompanyDataSet.Inbox).failure?.kind)
            assertEquals(0, source.active)
            model.onEvent(DataSyncEvent.DatasetRefreshRequested(CompanyDataSet.Inbox))
            runCurrent()
            source.read = { source.values }
            source.values.value = AppResult.Success(CompanyDataStatus(f.now + 1.hours))
            f.request(CompanyDataSet.Inbox).response.complete(AppResult.Success(TestSyncOutcome()))
            runCurrent()
            assertEquals(f.now + 1.hours, model.row(CompanyDataSet.Inbox).status?.lastCompletedAt)
            assertNull(model.row(CompanyDataSet.Inbox).failure)
            assertEquals(1, source.active)
            assertEquals(3, source.scopes.size)
        }
    }

    @Test
    fun localRetryCanRecoverStorageEvenWhenTheConnectionIsStillUnavailable() = runTest {
        withModel { f, model, _ ->
            val source = f.observation(CompanyDataSet.Inbox)
            source.read = { flowOf(AppResult.Failed(Failure(FailureKind.Storage, "Unavailable"))) }
            model.onEvent(DataSyncEvent.Entered)
            runCurrent()
            source.read = { source.values }
            f.request(CompanyDataSet.Inbox)
                .response
                .complete(AppResult.Failed(Failure(FailureKind.Network, "Offline")))
            model.onEvent(DataSyncEvent.DatasetRefreshRequested(CompanyDataSet.Inbox))
            runCurrent()
            assertEquals(f.now, model.row(CompanyDataSet.Inbox).status?.lastCompletedAt)
            assertNull(model.row(CompanyDataSet.Inbox).readFailure)
            assertEquals(FailureKind.Network, model.row(CompanyDataSet.Inbox).syncFailure?.kind)
        }
    }

    @Test
    fun stopCancelsRunningAndQueuedWorkWithoutStartingTheRemainingDatasets() = runTest {
        withModel { f, model, _ ->
            model.onEvent(DataSyncEvent.Entered)
            model.onEvent(DataSyncEvent.RefreshRequested)
            runCurrent()
            assertEquals(3, f.active)
            model.onEvent(DataSyncEvent.Stopped)
            runCurrent()
            assertEquals(0, f.active)
            assertEquals(3, f.requests.sumOf { it.scopes.size })
            assertFalse(model.state.value.syncing)
            assertTrue(f.observations.all { it.active == 1 })
            model.onEvent(DataSyncEvent.RefreshRequested)
            runCurrent()
            assertEquals(3, f.requests.sumOf { it.scopes.size })
            model.onEvent(DataSyncEvent.Entered)
            runCurrent()
            assertEquals(3, f.requests.sumOf { it.scopes.size })
        }
    }

    @Test
    fun cancellationRetainsSavedStatusAndTheNextExplicitRefreshCanRun() = runTest {
        withModel { f, model, _ ->
            model.onEvent(DataSyncEvent.Entered)
            model.onEvent(DataSyncEvent.DatasetRefreshRequested(CompanyDataSet.Inbox))
            runCurrent()
            model.onEvent(DataSyncEvent.CancelRequested)
            runCurrent()
            assertFalse(model.state.value.syncing)
            assertEquals(f.now, model.row(CompanyDataSet.Inbox).status?.lastCompletedAt)
            f.request(CompanyDataSet.Inbox).response = CompletableDeferred()
            model.onEvent(DataSyncEvent.DatasetRefreshRequested(CompanyDataSet.Inbox))
            runCurrent()
            assertEquals(2, f.request(CompanyDataSet.Inbox).scopes.size)
            f.request(CompanyDataSet.Inbox).response.complete(AppResult.Success(TestSyncOutcome()))
            runCurrent()
            assertFalse(model.state.value.syncing)
        }
    }

    @Test
    fun aLateSuccessAfterStopDoesNotOverwriteDenialOrRestartObservation() = runTest {
        withModel { f, model, _ ->
            val request = f.request(CompanyDataSet.Inbox)
            val release = CompletableDeferred<Unit>()
            request.execute = {
                withContext(NonCancellable) { release.await() }
                AppResult.Success(TestSyncOutcome())
            }
            try {
                model.onEvent(DataSyncEvent.Entered)
                model.onEvent(DataSyncEvent.DatasetRefreshRequested(CompanyDataSet.Inbox))
                runCurrent()
                val source = f.observation(CompanyDataSet.Inbox)
                val opens = source.scopes.size
                model.onEvent(DataSyncEvent.Stopped)
                val denial =
                    Failure(FailureKind.AccessDenied, "Access changed", code = "sync_scope_changed")
                source.values.value = AppResult.Failed(denial)
                runCurrent()
                release.complete(Unit)
                runCurrent()
                assertEquals(denial, model.row(CompanyDataSet.Inbox).failure)
                assertNull(model.row(CompanyDataSet.Inbox).status)
                assertEquals(opens, source.scopes.size)
                assertFalse(model.state.value.syncing)
                assertEquals(0, f.active)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun disposalCancelsPendingMetadataAndRejectsALateValue() = runTest {
        withModel { f, model, owner ->
            val release = CompletableDeferred<Unit>()
            val source = f.observation(CompanyDataSet.Inbox)
            source.read = {
                flow {
                    withContext(NonCancellable) { release.await() }
                    emit(AppResult.Success(CompanyDataStatus(f.now)))
                }
            }
            try {
                model.onEvent(DataSyncEvent.Entered)
                runCurrent()
                assertTrue(model.row(CompanyDataSet.Inbox).loading)
                owner.clear()
                runCurrent()
                release.complete(Unit)
                runCurrent()
                assertTrue(f.observations.all { it.active == 0 })
                assertNull(model.row(CompanyDataSet.Inbox).status)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun repeatedLocalRetriesWaitForCancelledStorageBeforeOpeningAnotherSource() = runTest {
        withModel { f, model, _ ->
            val release = CompletableDeferred<Unit>()
            val source = f.observation(CompanyDataSet.Inbox)
            source.read = {
                flow {
                    withContext(NonCancellable) { release.await() }
                    emit(AppResult.Success(CompanyDataStatus(f.now + 2.hours)))
                }
            }
            f.request(CompanyDataSet.Inbox)
                .response
                .complete(AppResult.Failed(Failure(FailureKind.Network, "Offline")))
            try {
                model.onEvent(DataSyncEvent.Entered)
                runCurrent()
                repeat(3) {
                    model.onEvent(DataSyncEvent.DatasetRefreshRequested(CompanyDataSet.Inbox))
                    runCurrent()
                    assertEquals(1, source.active)
                }
                source.read = { source.values }
                source.values.value = AppResult.Success(CompanyDataStatus(f.now + 1.hours))
                release.complete(Unit)
                runCurrent()
                assertEquals(1, source.active)
                assertEquals(2, source.scopes.size)
                assertEquals(
                    f.now + 1.hours,
                    model.row(CompanyDataSet.Inbox).status?.lastCompletedAt,
                )
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun backCancelsAcquisitionAndOnlyEmitsNavigation() = runTest {
        withModel { f, model, _ ->
            val effects = mutableListOf<DataSyncEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(DataSyncEvent.Entered)
            model.onEvent(DataSyncEvent.RefreshRequested)
            runCurrent()
            model.onEvent(DataSyncEvent.BackRequested)
            model.onEvent(DataSyncEvent.BackRequested)
            runCurrent()
            assertEquals(listOf<DataSyncEffect>(DataSyncEffect.Back), effects)
            assertEquals(0, f.active)
            assertFalse(model.state.value.syncing)
        }
    }

    private suspend fun TestScope.withModel(
        test: suspend (DataSyncFixture, DataSyncViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = DataSyncFixture()
        val model = fixture.model()
        val owner = ViewModelStore().apply { put("data-sync", model) }
        try {
            test(fixture, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
