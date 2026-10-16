@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workforce.presentation.overtime

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.workforce.presentation.overtime.action.*
import dev.fajar.starter.workforce.presentation.overtime.detail.*
import dev.fajar.starter.workforce.presentation.overtime.plan.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*

class OvertimeAdmissionViewModelTest {
    private val denied =
        AppResult.Failed(Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required"))

    @Test
    fun aPlanObserverDoesNotCancelPersistenceOfItsOwnReadDenial() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val release = CompletableDeferred<Unit>()
        try {
            val f = OvertimePlanFixture(StandardTestDispatcher(testScheduler))
            val vm = f.create(store)
            vm.onEvent(OvertimePlanEvent.Entered)
            runCurrent()
            assertNotNull(vm.state.value.prepared)
            f.base.replicas.beforeAdmissionWrite = { release.await() }
            f.read = { denied }
            vm.onEvent(OvertimePlanEvent.ReloadRequested)
            runCurrent()
            assertTrue(vm.state.value.needsVerification)
            assertNull(vm.state.value.prepared)
            assertEquals(0, f.observers)
            assertEquals(0, f.base.replicas.admissionWrites)
            release.complete(Unit)
            runCurrent()
            assertEquals(1, f.base.replicas.admissionWrites)
            assertNull(vm.state.value.work)
        } finally {
            release.complete(Unit)
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun anActionObserverDoesNotCancelPersistenceOfItsOwnReadDenial() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val release = CompletableDeferred<Unit>()
        try {
            val f = OvertimeActionFixture(StandardTestDispatcher(testScheduler))
            val vm = f.create(store)
            vm.onEvent(OvertimeActionEvent.Entered)
            runCurrent()
            assertNotNull(vm.state.value.content)
            f.base.replicas.beforeAdmissionWrite = { release.await() }
            f.base.detailsCall = { denied }
            vm.onEvent(OvertimeActionEvent.RefreshRequested)
            runCurrent()
            assertTrue(vm.state.value.needsVerification)
            assertNull(vm.state.value.content)
            assertNull(vm.state.value.form)
            assertEquals(0, f.base.replicas.admissionWrites)
            release.complete(Unit)
            runCurrent()
            assertEquals(1, f.base.replicas.admissionWrites)
            assertNull(vm.state.value.work)
        } finally {
            release.complete(Unit)
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aReplacementPlanCannotReopenAfterPartialOrFailedReadmission() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var store = ViewModelStore()
        try {
            val f = OvertimePlanFixture(StandardTestDispatcher(testScheduler))
            f.base.replicas.failAdmissionWrite = true
            var vm = f.create(store)
            val accepted = f.read
            f.read = { denied }
            vm.onEvent(OvertimePlanEvent.Entered)
            runCurrent()
            assertTrue(vm.state.value.needsVerification)
            store.clear()
            runCurrent()
            store = ViewModelStore()
            vm = f.create(store)
            f.read = accepted
            val bootstrap = f.base.bootstrapCall
            f.base.bootstrapCall = {
                AppResult.Success(
                    SyncBootstrapPage(
                        setOf(SyncCollection.OVERTIME_REQUESTS),
                        emptyList(),
                        "page-${f.base.bootstrapReads}",
                        null,
                        f.base.clock.now(),
                    )
                )
            }
            vm.onEvent(OvertimePlanEvent.Entered)
            runCurrent()
            assertTrue(vm.state.value.needsVerification)
            vm.onEvent(OvertimePlanEvent.ReloadRequested)
            runCurrent()
            assertEquals(10, f.base.bootstrapReads)
            assertTrue(vm.state.value.needsVerification)
            assertNull(vm.state.value.prepared)
            assertEquals(1, f.reads)
            f.base.bootstrapCall = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
            vm.onEvent(OvertimePlanEvent.ReloadRequested)
            runCurrent()
            assertTrue(vm.state.value.needsVerification)
            assertNull(vm.state.value.prepared)
            f.base.bootstrapCall = bootstrap
            vm.onEvent(OvertimePlanEvent.ReloadRequested)
            runCurrent()
            assertNull(vm.state.value.restriction)
            assertNotNull(vm.state.value.prepared)
            assertEquals(2, f.reads)
            assertTrue(vm.state.value.editable)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun detailDisposalCancelsPendingRecoveryAndKeepsTheGate() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val release = CompletableDeferred<Unit>()
        try {
            val f = OvertimePresentationFixture()
            f.replicas.restrict(f.access, denied.failure)
            val acquired = f.bootstrapCall
            val entered = CompletableDeferred<Unit>()
            f.bootstrapCall = { cursor ->
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    acquired(cursor)
                }
            }
            val vm =
                OvertimeRequestViewModel(
                    OvertimeRequestTarget(f.workspace.id, f.selection),
                    f.observeRequest,
                    f.refreshRequest,
                    f.sync,
                )
            store.put("detail", vm)
            vm.onEvent(OvertimeRequestEvent.Entered)
            runCurrent()
            assertTrue(entered.isCompleted)
            assertTrue(vm.state.value.needsVerification)
            assertNull(vm.state.value.content)
            vm.onEvent(OvertimeRequestEvent.Stopped)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(denied.failure, f.replicas.readAdmission.value.denial)
            assertEquals(0, f.replicas.admissionObservers)
            assertNull(vm.state.value.content)
            f.bootstrapCall = acquired
            vm.onEvent(OvertimeRequestEvent.Entered)
            runCurrent()
            assertFalse(vm.state.value.readRestricted)
            assertNotNull(vm.state.value.content)
        } finally {
            release.complete(Unit)
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun companyAccessLossAfterMfaRequestsWorkspaceRevalidationOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = OvertimePlanFixture(StandardTestDispatcher(testScheduler))
            f.read = { denied }
            val vm = f.create(store)
            val effects = mutableListOf<OvertimePlanEffect>()
            val observer =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    vm.effects.toList(effects)
                }
            vm.onEvent(OvertimePlanEvent.Entered)
            runCurrent()
            assertTrue(vm.state.value.needsVerification)
            assertTrue(effects.isEmpty())
            f.base.bootstrapCall = {
                AppResult.Failed(
                    Failure(FailureKind.AccessDenied, "Denied", code = "company_access_denied")
                )
            }
            repeat(2) {
                vm.onEvent(OvertimePlanEvent.ReloadRequested)
                runCurrent()
                assertEquals("company_access_denied", vm.state.value.restriction?.code)
                assertNull(vm.state.value.prepared)
                assertNull(vm.state.value.command)
            }
            assertEquals(listOf<OvertimePlanEffect>(OvertimePlanEffect.RecheckWorkspace), effects)
            observer.cancelAndJoin()
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
