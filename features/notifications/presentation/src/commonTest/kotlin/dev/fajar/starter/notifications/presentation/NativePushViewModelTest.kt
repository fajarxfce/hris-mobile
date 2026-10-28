@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.notifications.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.push.entities.*
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import dev.fajar.starter.notifications.presentation.nativepush.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

class NativePushViewModelTest {
    @Test
    fun enableObservesRotationAndStopResumeDoesNotReplayOldCallbacksOrPrompt() = runTest {
        withFixture { f, vm, _ ->
            f.permission = NotificationAccess.Denied
            start(vm)
            assertEquals(0, f.permissionRequests)
            assertTrue(f.commands.isEmpty())
            vm.onEvent(NativePushEvent.EnableRequested)
            vm.onEvent(NativePushEvent.EnableRequested)
            runCurrent()
            assertEquals(1, f.permissionRequests)
            assertTrue(vm.state.value.status!!.registered)
            assertEquals(1, f.commands.size)
            assertEquals(1, f.listeners)
            f.providerToken.value = "provider-token-changed"
            runCurrent()
            assertEquals(2, f.commands.size)
            assertEquals(1, f.listeners)
            vm.onEvent(NativePushEvent.Stopped)
            runCurrent()
            assertEquals(0, f.listeners)
            f.providerToken.value = "provider-token-after-stop"
            runCurrent()
            assertEquals(2, f.commands.size)
            start(vm)
            assertEquals(3, f.commands.size)
            assertEquals(1, f.permissionRequests)
            vm.onEvent(NativePushEvent.DisableRequested)
            runCurrent()
            assertFalse(vm.state.value.status!!.registered)
            assertEquals(0, f.listeners)
            assertNull(f.saved.value.token)
        }
    }

    @Test
    fun stoppedPermissionRejectsItsLateResultAndRetainsAnExplicitRetry() = runTest {
        withFixture { f, vm, _ ->
            val release = CompletableDeferred<Unit>()
            f.permission = NotificationAccess.Denied
            f.beforePermission = { withContext(NonCancellable) { release.await() } }
            start(vm)
            vm.onEvent(NativePushEvent.EnableRequested)
            runCurrent()
            assertTrue(vm.state.value.busy)
            vm.onEvent(NativePushEvent.Stopped)
            runCurrent()
            assertFalse(vm.state.value.busy)
            start(vm)
            assertTrue(f.commands.isEmpty())
            release.complete(Unit)
            runCurrent()
            assertTrue(f.commands.isEmpty())
            assertFalse(f.saved.value.desired)
            vm.onEvent(NativePushEvent.EnableRequested)
            runCurrent()
            assertEquals(1, f.commands.size)
            assertTrue(vm.state.value.status!!.registered)
        }
    }

    @Test
    fun lostResponseKeepsThePreferenceAndRetryCannotUndoLaterDisable() = runTest {
        withFixture { f, vm, _ ->
            start(vm)
            f.loseResponse = true
            vm.onEvent(NativePushEvent.EnableRequested)
            runCurrent()
            val pending = assertNotNull(f.saved.value.pending)
            assertTrue(vm.state.value.status!!.enabled)
            assertTrue(vm.state.value.status!!.pending)
            assertNotNull(vm.state.value.failure)
            assertEquals(0, f.listeners)
            f.remote.replaceAllForTest { it.copy(enabled = false, version = it.version + 1) }
            f.loseResponse = false
            vm.onEvent(NativePushEvent.RetryRequested)
            runCurrent()
            assertEquals(listOf(pending, pending), f.commands.map { it.second })
            assertFalse(vm.state.value.status!!.enabled)
            assertFalse(vm.state.value.status!!.registered)
            assertNull(f.saved.value.pending)
            assertNull(f.saved.value.token)
            assertEquals(0, f.listeners)
        }
    }

    @Test
    fun replacementAccountClearsStatusAndWaitsForObsoleteTransportBeforeRegistering() = runTest {
        withFixture { f, vm, _ ->
            start(vm)
            val release = CompletableDeferred<Unit>()
            f.beforeSend = { withContext(NonCancellable) { release.await() } }
            vm.onEvent(NativePushEvent.EnableRequested)
            runCurrent()
            val old = assertNotNull(f.saved.value.pending)
            val replacement =
                f.original.copy(
                    id = "second",
                    user = f.original.user.copy(id = "33333333-3333-4333-8333-333333333333"),
                    binding =
                        f.original.binding.copy(serverId = "44444444-4444-4444-8444-444444444444"),
                )
            f.identities.value = AppResult.Success(replacement)
            runCurrent()
            assertEquals("second", vm.state.value.sessionId)
            assertFalse(vm.state.value.status?.registered == true)
            assertEquals(1, f.commands.size)
            release.complete(Unit)
            runCurrent()
            assertEquals(2, f.commands.size)
            assertEquals(listOf("first", "second"), f.commands.map { it.first.localSessionId })
            assertNotEquals(old.operationId, f.commands.last().second.operationId)
            assertTrue(f.revoked.isEmpty())
            assertEquals(replacement.pushOwner(), f.saved.value.owner)
            assertTrue(vm.state.value.status!!.registered)
        }
    }

    @Test
    fun failedIdentityReadCanBeRetriedWithoutRecreatingTheApplication() = runTest {
        withFixture { f, vm, _ ->
            f.identities.value = f.failed(FailureKind.Storage)
            start(vm)
            assertNull(vm.state.value.status)
            assertNotNull(vm.state.value.failure)
            f.identities.value = AppResult.Success(f.original)
            vm.onEvent(NativePushEvent.RetryRequested)
            runCurrent()
            assertEquals("first", vm.state.value.sessionId)
            assertNotNull(vm.state.value.status)
            assertNull(vm.state.value.failure)
        }
    }

    @Test
    fun expiredCredentialsKeepOwnedLocalRegistrationMetadataReadable() = runTest {
        withFixture { f, vm, _ ->
            start(vm)
            vm.onEvent(NativePushEvent.EnableRequested)
            runCurrent()
            val expiry = f.saved.value.registration!!.expiresAt
            f.now += 100.days
            val local =
                assertIs<AppResult.Success<NativePushStatus>>(f.status()("first").first()).value
            assertEquals(expiry, local.expiresAt)
            assertTrue(local.enabled)
            assertFalse(local.registered)
            vm.onEvent(NativePushEvent.RetryRequested)
            runCurrent()
            assertEquals(expiry, vm.state.value.status!!.expiresAt)
            assertFalse(vm.state.value.status!!.registered)
            assertEquals(FailureKind.Unauthorized, vm.state.value.failure!!.kind)
        }
    }

    @Test
    fun disposalCancelsPendingRegistrationWithoutLosingItsFrozenCommand() = runTest {
        withFixture { f, vm, owner ->
            start(vm)
            val release = CompletableDeferred<Unit>()
            f.beforeSend = { withContext(NonCancellable) { release.await() } }
            vm.onEvent(NativePushEvent.EnableRequested)
            runCurrent()
            val pending = assertNotNull(f.saved.value.pending)
            owner.clear()
            release.complete(Unit)
            runCurrent()
            assertEquals(pending, f.saved.value.pending)
            assertFalse(vm.state.value.status?.registered == true)
            assertEquals(0, f.listeners)
        }
    }

    private fun TestScope.start(vm: NativePushViewModel) {
        vm.onEvent(NativePushEvent.Started)
        runCurrent()
    }

    private suspend fun TestScope.withFixture(
        block: suspend TestScope.(NativePushUiFixture, NativePushViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val fixture = NativePushUiFixture()
            val vm = fixture.viewModel()
            owner.put("push", vm)
            block(fixture, vm, owner)
        } finally {
            owner.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private fun MutableMap<String, NativePushRegistration>.replaceAllForTest(
        update: (NativePushRegistration) -> NativePushRegistration
    ) {
        keys.toList().forEach { key -> this[key] = update(getValue(key)) }
    }
}
