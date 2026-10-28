@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.identity.domain

import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.push.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class NativePushBackgroundTest {
    @Test
    fun emptyAndUnsupportedInstallationsDoNotAcquireOrRegisterTokens() = runTest {
        val f = NativePushFixture()
        assertIs<AppResult.Success<*>>(f.useCase()("local", NativePushAction.Background))
        assertIs<AppResult.Success<*>>(
            f.useCase(AppPlatform.Desktop)("local", NativePushAction.Background)
        )
        assertEquals(NativePushState(), f.state)
        assertEquals(0, f.deletes)
        assertEquals(0, f.acquisitions)
        assertEquals(0, f.reads)
    }

    @Test
    fun backgroundRotationAndExpiryRenewWithoutPromptingAndSuccessfulWorkClearsItsBudget() =
        runTest {
            val f = NativePushFixture()
            assertIs<AppResult.Success<*>>(f.useCase()("local", NativePushAction.Enable))
            f.token = "provider-token-rotated-in-background"
            assertIs<AppResult.Success<*>>(f.useCase()("local", NativePushAction.Background))
            assertEquals(2, f.commands.size)
            assertEquals(f.token, (f.commands.last().second as NativePushCommand.Save).token)
            assertEquals(0, f.state.automaticAttempts)
            assertNull(f.state.automaticRetryAfter)
            f.now += 29.days
            assertIs<AppResult.Success<*>>(f.useCase()("local", NativePushAction.Background))
            assertEquals(3, f.commands.size)
        }

    @Test
    fun automaticFailuresBackOffAndStopAcrossNewCoordinatorsUntilForegroundRecovery() = runTest {
        val f = NativePushFixture()
        f.state = NativePushState(desired = true, retiringToken = true)
        f.failDeletion = true
        repeat(8) { index ->
            assertIs<AppResult.Failed>(f.useCase()("local", NativePushAction.Background))
            assertEquals(index + 1, f.state.automaticAttempts)
            assertEquals(index + 1, f.deletes)
            val retry =
                assertIs<AppResult.Failed>(f.useCase()("local", NativePushAction.Background))
            assertEquals(
                if (index == 7) "client.push_retry_limit" else "client.push_retry_later",
                retry.failure.code,
            )
            assertEquals(index + 1, f.deletes)
            f.now = assertNotNull(f.state.automaticRetryAfter)
        }
        f.now += 1.days
        assertEquals(
            "client.push_retry_limit",
            assertIs<AppResult.Failed>(f.useCase()("local", NativePushAction.Background))
                .failure
                .code,
        )
        assertEquals(8, f.deletes)
        f.failDeletion = false
        assertIs<AppResult.Success<*>>(f.useCase()("local"))
        assertEquals(0, f.state.automaticAttempts)
        assertNull(f.state.automaticRetryAfter)
        assertTrue(f.state.registration!!.enabled)
    }

    @Test
    fun cancellationRetainsTheAttemptAndFrozenCommandForBoundedRecovery() = runTest {
        val f = NativePushFixture()
        f.state = NativePushState(desired = true)
        val release = CompletableDeferred<Unit>()
        f.beforeSend = { withContext(NonCancellable) { release.await() } }
        val operation = launch { f.useCase()("local", NativePushAction.Background) }
        runCurrent()
        val command = assertNotNull(f.state.pending)
        assertEquals(1, f.state.automaticAttempts)
        operation.cancel()
        release.complete(Unit)
        operation.join()
        assertEquals(command, f.state.pending)
        f.now = assertNotNull(f.state.automaticRetryAfter)
        assertIs<AppResult.Success<*>>(f.useCase()("local", NativePushAction.Background))
        assertEquals(listOf(command, command), f.commands.map { it.second })
        assertEquals(0, f.state.automaticAttempts)
    }

    @Test
    fun aForegroundDisableWaitsForTheWorkerAndRetiresItsRegistration() = runTest {
        val f = NativePushFixture()
        f.state = NativePushState(desired = true)
        val release = CompletableDeferred<Unit>()
        f.beforeSend = { release.await() }
        val background = async { f.useCase()("local", NativePushAction.Background) }
        runCurrent()
        val disable = async { f.useCase()("local", NativePushAction.Disable) }
        runCurrent()
        assertEquals(1, f.commands.size)
        release.complete(Unit)
        assertIs<AppResult.Success<*>>(background.await())
        assertIs<AppResult.Success<*>>(disable.await())
        assertIs<NativePushCommand.Disable>(f.commands.last().second)
        assertFalse(f.state.desired)
        assertNull(f.state.token)
        assertEquals(0, f.state.automaticAttempts)
    }

    @Test
    fun failureToPersistTheAutomaticAttemptPreventsProviderAndBackendIo() = runTest {
        val f = NativePushFixture()
        f.state = NativePushState(desired = true)
        f.failStorage = true
        assertIs<AppResult.Failed>(f.useCase()("local", NativePushAction.Background))
        assertEquals(0, f.deletes)
        assertEquals(0, f.acquisitions)
        assertEquals(0, f.reads)
        assertTrue(f.commands.isEmpty())
        assertEquals(0, f.state.automaticAttempts)
    }
}
