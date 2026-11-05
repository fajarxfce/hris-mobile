@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class SynchronizeCollaborationDeviceTest {
    @Test
    fun anOwnedDeviceInitializesWithoutConversationOrMessageOperations() = runTest {
        val f = CollaborationDeviceOperationFixture()
        repeat(2) { assertEquals(AppResult.Success(Unit), f.synchronize("selection", f.device)) }
        assertEquals(2, f.calls)
        assertEquals(f.setup.initial, f.setup.session)
        assertEquals(f.setup.selected, f.setup.workspace)
    }

    @Test
    fun backgroundActivationNeedsNoForegroundCompanyAndKeepsItsExactDevice() = runTest {
        val f = CollaborationDeviceOperationFixture()
        val scope = f.background()
        assertEquals(AppResult.Success(Unit), f.synchronize(scope, f.device))
        assertNull(f.setup.workspace)
        assertEquals(1, f.calls)
        assertIs<AppResult.Failed>(f.synchronize(scope, "a0000000-0000-4000-8000-000000000009"))
        assertEquals(1, f.calls)
    }

    @Test
    fun inactiveOrDifferentDevicesCannotInitialize() = runTest {
        for (mode in 0..3) {
            val f = CollaborationDeviceOperationFixture()
            f.setup.onRead = {
                AppResult.Success(
                    when (mode) {
                        0 -> null
                        1 -> f.setup.device
                        2 -> f.registered.copy(state = CollaborationDeviceState.PROVISIONING)
                        else -> f.registered.copy(platform = CollaborationNativePlatform.IOS)
                    }
                )
            }
            assertIs<AppResult.Failed>(f.synchronize("selection", f.device))
            assertEquals(0, f.calls)
        }
    }

    @Test
    fun changingTheNativeParentDuringSyncCannotAcknowledgeCompletion() = runTest {
        val f = CollaborationDeviceOperationFixture()
        f.onActivate = {
            f.setup.session = f.setup.initial.copy(id = "different-native-parent")
            AppResult.Success(Unit)
        }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.synchronize("selection", f.device)).failure.kind,
        )
        assertEquals(1, f.calls)
    }

    @Test
    fun aRemoteDenialStaysGatedOnTheNextAttempt() = runTest {
        val f = CollaborationDeviceOperationFixture()
        f.onActivate = { AppResult.Failed(f.denied) }
        assertEquals(
            f.denied,
            assertIs<AppResult.Failed>(f.synchronize("selection", f.device)).failure,
        )
        f.onActivate = { AppResult.Success(Unit) }
        assertEquals(
            f.denied,
            assertIs<AppResult.Failed>(f.synchronize("selection", f.device)).failure,
        )
        assertEquals(1, f.calls)
    }

    @Test
    fun expiryDuringDeviceLookupStopsBeforeStartingSync() = runTest {
        val f = CollaborationDeviceOperationFixture()
        val scope = f.background()
        f.setup.onRead = {
            f.setup.now += 61.seconds
            AppResult.Success(f.registered)
        }
        assertIs<AppResult.Failed>(f.synchronize(scope, f.device))
        assertEquals(0, f.calls)
    }

    @Test
    fun concurrentAndCancelledAttemptsNeverStartDuplicateInitialization() = runTest {
        val f = CollaborationDeviceOperationFixture()
        f.onActivate = { awaitCancellation() }
        val first = async { f.synchronize("selection", f.device) }
        runCurrent()
        assertEquals(
            "client.collaboration_device_sync_busy",
            assertIs<AppResult.Failed>(f.synchronize("selection", f.device)).failure.code,
        )
        assertEquals(1, f.calls)
        first.cancelAndJoin()
        f.onActivate = { AppResult.Success(Unit) }
        assertEquals(AppResult.Success(Unit), f.synchronize("selection", f.device))
        assertEquals(2, f.calls)
    }
}
