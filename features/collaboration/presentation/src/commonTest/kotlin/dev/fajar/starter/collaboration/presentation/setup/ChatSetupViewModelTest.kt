@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.setup

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class ChatSetupViewModelTest {
    @Test
    fun entryReadsOnceWithoutEnrollingAndDisposalReleasesOnlyObservation() = runTest {
        withModel { f, vm, store, _ ->
            assertEquals(0, f.reads)
            vm.onEvent(ChatSetupEvent.Entered)
            vm.onEvent(ChatSetupEvent.Entered)
            runCurrent()
            assertEquals(ChatSetupPhase.REGISTER, vm.state.value.phase)
            assertEquals(1, f.reads)
            assertTrue(f.sends.isEmpty())
            vm.onEvent(ChatSetupEvent.Stopped)
            runCurrent()
            vm.onEvent(ChatSetupEvent.ContinueRequested)
            vm.onEvent(ChatSetupEvent.RefreshRequested)
            runCurrent()
            assertEquals(1, f.reads)
            assertEquals(1, f.base.session.subscriptionCount.value)
            store.clear()
            runCurrent()
            assertEquals(0, f.base.session.subscriptionCount.value)
            assertEquals(0, f.closes)
        }
    }

    @Test
    fun offlineEntryKeepsRetainedIntentWithoutReplayingExpiredAuthority() = runTest {
        withModel { f, vm, _, _ ->
            val retained = f.retained()
            f.base.now += 30.days
            vm.onEvent(ChatSetupEvent.Entered)
            runCurrent()
            assertEquals(ChatSetupPhase.RESUME, vm.state.value.phase)
            assertEquals(ChatSetupOperation.REGISTER, vm.state.value.retainedOperation)
            assertNotNull(vm.state.value.failure)
            assertSame(retained, f.record)
            assertTrue(f.sends.isEmpty())
            assertEquals(0, f.reads)
        }
    }

    @Test
    fun entryCanRecoverKnownCompanyDenialButFailedRecoveryKeepsItClosed() = runTest {
        withModel { f, vm, _, _ ->
            f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
            f.read = { AppResult.Failed(f.base.network) }
            vm.onEvent(ChatSetupEvent.Entered)
            runCurrent()
            assertEquals(f.base.denied, vm.state.value.localFailure)
            assertEquals(f.base.denied, vm.state.value.failure)
            f.read = { AppResult.Success(null) }
            vm.onEvent(ChatSetupEvent.RefreshRequested)
            runCurrent()
            assertEquals(ChatSetupPhase.REGISTER, vm.state.value.phase)
            assertNull(vm.state.value.localFailure)
            assertNull(f.base.gate.value.denial)
        }
    }

    @Test
    fun lostRegistrationResponseReusesOneOperationAndObservedReceiptRetires() = runTest {
        withModel { f, vm, _, _ ->
            vm.onEvent(ChatSetupEvent.Entered)
            runCurrent()
            val normal = f.register
            f.register = {
                normal(it)
                AppResult.Failed(f.base.network)
            }
            vm.onEvent(ChatSetupEvent.ContinueRequested)
            runCurrent()
            val retained = assertNotNull(f.record)
            assertIs<CollaborationSetupOutcome.Unconfirmed>(retained.outcome)
            assertEquals(ChatSetupPhase.RESUME, vm.state.value.phase)
            f.register = normal
            vm.onEvent(ChatSetupEvent.ContinueRequested)
            runCurrent()
            assertEquals(listOf(retained.command.id, retained.command.id), f.sends)
            assertNull(f.record)
            assertEquals(1, f.retired)
            assertEquals(ChatSetupPhase.WAITING, vm.state.value.phase)
            assertEquals(0, f.installations)
        }
    }

    @Test
    fun confirmedRegistrationAfterUpgradeReconcilesWithoutResending() = runTest {
        withModel { f, vm, _, _ ->
            f.device = f.active.copy(state = CollaborationDeviceState.PROVISIONING)
            val retained =
                f.retained(
                    outcome =
                        CollaborationSetupOutcome.Registered(
                            CollaborationSetupReceipt(f.active.id, 0)
                        )
                )
            f.record = retained.copy(command = retained.command.copy(clientBuild = 999))
            vm.onEvent(ChatSetupEvent.Entered)
            runCurrent()
            vm.onEvent(ChatSetupEvent.ContinueRequested)
            runCurrent()
            assertTrue(f.sends.isEmpty())
            assertNull(f.record)
            assertEquals(ChatSetupPhase.WAITING, vm.state.value.phase)
        }
    }

    @Test
    fun unobservedReceiptStaysRecoverableWithoutGuessingANewAction() = runTest {
        withModel { f, vm, _, _ ->
            val retained =
                f.retained(
                    outcome =
                        CollaborationSetupOutcome.Registered(
                            CollaborationSetupReceipt(f.active.id, 0)
                        )
                )
            vm.onEvent(ChatSetupEvent.Entered)
            runCurrent()
            repeat(2) {
                vm.onEvent(ChatSetupEvent.ContinueRequested)
                runCurrent()
            }
            assertSame(retained, f.record)
            assertEquals(ChatSetupPhase.RESUME, vm.state.value.phase)
            assertTrue(f.sends.isEmpty())
            assertEquals(0, f.retired)
        }
    }

    @Test
    fun confirmedBootstrapTransfersRuntimeAndReadySurvivesEffectConsumption() = runTest {
        withModel { f, vm, store, effects ->
            f.device = f.active
            f.retained(
                CollaborationSetupAction.Bootstrap(f.active.id, f.active.version, null),
                CollaborationSetupOutcome.Bootstrapped(f.gateway()),
            )
            vm.onEvent(ChatSetupEvent.Entered)
            runCurrent()
            assertEquals(0, f.restores)
            vm.onEvent(ChatSetupEvent.ContinueRequested)
            runCurrent()
            assertEquals(1, f.installations)
            assertTrue(f.sends.isEmpty())
            assertNull(f.record)
            assertEquals(ChatSetupPhase.READY, vm.state.value.phase)
            assertEquals(f.active.id, vm.state.value.readyDeviceId)
            assertTrue(effects.isEmpty())
            vm.onEvent(ChatSetupEvent.OpenChatRequested)
            vm.onEvent(ChatSetupEvent.OpenChatRequested)
            runCurrent()
            assertEquals<List<ChatSetupEffect>>(listOf(ChatSetupEffect.Ready(f.active.id)), effects)
            assertEquals(f.active.id, vm.state.value.readyDeviceId)
            store.clear()
            runCurrent()
            assertEquals(0, f.closes)
            f.lease.close()
        }
    }

    @Test
    fun activeDeviceRestoresWithoutNewBootstrapOrEnrollment() = runTest {
        withModel { f, vm, _, _ ->
            f.device = f.active
            f.restore = {
                f.opened = true
                AppResult.Success(f.lease)
            }
            vm.onEvent(ChatSetupEvent.Entered)
            runCurrent()
            assertEquals(ChatSetupPhase.READY, vm.state.value.phase)
            assertEquals(1, f.restores)
            assertTrue(f.sends.isEmpty())
            assertEquals(0, f.installations)
            vm.onEvent(ChatSetupEvent.Stopped)
            runCurrent()
            vm.onEvent(ChatSetupEvent.Entered)
            runCurrent()
            assertEquals(1, f.restores)
            f.lease.close()
        }
    }

    @Test
    fun stoppingAnUncooperativeBootstrapKeepsItsIntentForExplicitResume() = runTest {
        withModel { f, vm, _, _ ->
            f.device = f.active
            vm.onEvent(ChatSetupEvent.Entered)
            runCurrent()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.bootstrap = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    AppResult.Success(f.gateway())
                }
            }
            try {
                vm.onEvent(ChatSetupEvent.ContinueRequested)
                runCurrent()
                entered.await()
                val id = assertNotNull(f.record).command.id
                vm.onEvent(ChatSetupEvent.Stopped)
                runCurrent()
                release.complete(Unit)
                runCurrent()
                assertEquals(0, f.installations)
                assertIs<CollaborationSetupOutcome.Unconfirmed>(f.record!!.outcome)
                vm.onEvent(ChatSetupEvent.Entered)
                runCurrent()
                assertEquals(listOf(id), f.sends)
                assertEquals(ChatSetupPhase.RESUME, vm.state.value.phase)
                f.bootstrap = { AppResult.Success(f.gateway()) }
                vm.onEvent(ChatSetupEvent.ContinueRequested)
                runCurrent()
                assertEquals(listOf(id, id), f.sends)
                assertEquals(ChatSetupPhase.READY, vm.state.value.phase)
                f.lease.close()
            } finally {
                release.complete(Unit)
                runCurrent()
            }
        }
    }

    @Test
    fun replacingTheAccountDuringLateInstallationClosesItsResource() = runTest {
        withModel { f, vm, _, effects ->
            f.device = f.active
            f.retained(
                CollaborationSetupAction.Bootstrap(f.active.id, 1, null),
                CollaborationSetupOutcome.Bootstrapped(f.gateway()),
            )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeInstall = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            try {
                vm.onEvent(ChatSetupEvent.Entered)
                runCurrent()
                vm.onEvent(ChatSetupEvent.ContinueRequested)
                runCurrent()
                entered.await()
                f.base.session.value = null
                runCurrent()
                release.complete(Unit)
                runCurrent()
                assertNotNull(vm.state.value.localFailure)
                assertNull(vm.state.value.readyDeviceId)
                assertNull(vm.state.value.retainedOperation)
                assertNotNull(f.record)
                assertEquals(1, f.closes)
                assertTrue(effects.isEmpty())
            } finally {
                release.complete(Unit)
                runCurrent()
            }
        }
    }

    @Test
    fun hiddenCompanyLossClearsOwnedSetupAndDoesNotNavigate() = runTest {
        withModel { f, vm, store, effects ->
            f.retained()
            vm.onEvent(ChatSetupEvent.Entered)
            runCurrent()
            vm.onEvent(ChatSetupEvent.Stopped)
            runCurrent()
            f.base.workspace.value = null
            runCurrent()
            assertNull(vm.state.value.retainedOperation)
            assertNull(vm.state.value.device)
            assertNotNull(vm.state.value.localFailure)
            vm.onEvent(ChatSetupEvent.BackRequested)
            runCurrent()
            assertTrue(effects.isEmpty())
            store.clear()
            runCurrent()
            assertEquals(0, f.base.session.subscriptionCount.value)
        }
    }

    private suspend fun TestScope.withModel(
        block:
            suspend TestScope.(
                ChatSetupFixture, ChatSetupViewModel, ViewModelStore, MutableList<ChatSetupEffect>,
            ) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ChatSetupFixture()
        val vm = fixture.model()
        val store = ViewModelStore().also { it.put("setup", vm) }
        val effects = mutableListOf<ChatSetupEffect>()
        val collector =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.effects.collect { effects += it }
            }
        try {
            block(fixture, vm, store, effects)
        } finally {
            store.clear()
            runCurrent()
            collector.cancel()
            Dispatchers.resetMain()
        }
    }
}
