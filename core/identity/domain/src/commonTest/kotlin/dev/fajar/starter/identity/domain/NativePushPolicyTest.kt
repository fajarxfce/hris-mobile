@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.identity.domain

import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.push.entities.*
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class NativePushPolicyTest {
    @Test
    fun anAbsentOwnedFamilyCompletesRetirementWithoutTouchingAnotherAccount() = runTest {
        val f = NativePushFixture()
        val owner = requireNotNull(f.session).pushOwner()
        val previous = owner.copy(familyId = "33333333-3333-4333-8333-333333333333")
        val other = previous.copy(accountId = "44444444-4444-4444-8444-444444444444")
        f.state = NativePushState(revocations = listOf(previous, other))
        f.revokeFailure =
            Failure(FailureKind.Unexpected, "Unavailable family", code = "native_session_not_found")
        val result =
            assertIs<AppResult.Success<NativePushStatus>>(
                f.useCase()("local", NativePushAction.Enable)
            )
        assertTrue(result.value.registered)
        assertEquals(listOf("local" to previous.familyId), f.revocations)
        assertEquals(listOf(other), f.state.revocations)
        assertEquals(1, f.commands.size)
        assertNull(f.state.pending)
    }

    @Test
    fun retirementKeepsOtherFailuresAndOnlyRetriesWhenInvoked() = runTest {
        for (failure in
            listOf(
                Failure(FailureKind.Unexpected, "Unknown", code = "other_not_found"),
                Failure(FailureKind.Service, "Service", code = "native_session_not_found"),
                Failure(FailureKind.AccessDenied, "Denied", code = "native_session_not_found"),
                Failure(FailureKind.Unauthorized, "Revoked", code = "session_revoked"),
                Failure(FailureKind.Network, "Offline"),
            )) {
            val f = NativePushFixture()
            val previous =
                requireNotNull(f.session)
                    .pushOwner()
                    .copy(familyId = "33333333-3333-4333-8333-333333333333")
            f.state = NativePushState(revocations = listOf(previous))
            f.revokeFailure = failure
            assertEquals(
                failure,
                assertIs<AppResult.Failed>(f.useCase()("local", NativePushAction.Enable)).failure,
            )
            assertEquals(listOf(previous), f.state.revocations)
            assertTrue(f.commands.isEmpty())
            assertEquals(1, f.revocations.size)
            f.revokeFailure = null
            assertIs<AppResult.Success<NativePushStatus>>(f.useCase()("local"))
            assertTrue(f.state.revocations.isEmpty())
        }
    }

    @Test
    fun explicitEnablePersistsBeforeTransportAndUnchangedForegroundDoesNotRenew() = runTest {
        val f = NativePushFixture()
        f.beforeSend = { assertEquals(f.commands.last().second, f.state.pending) }
        val ready =
            assertIs<AppResult.Success<NativePushStatus>>(
                    f.useCase()("local", NativePushAction.Enable)
                )
                .value
        assertTrue(ready.registered)
        assertEquals(1, f.deletes)
        assertEquals(1, f.commands.size)
        assertNull(f.state.pending)
        repeat(2) { assertIs<AppResult.Success<NativePushStatus>>(f.useCase()("local")) }
        assertEquals(1, f.commands.size)
        f.token = "provider-rotated-token"
        assertIs<AppResult.Success<NativePushStatus>>(f.useCase()("local"))
        assertEquals(2, f.commands.size)
        assertEquals(0L, f.commands.last().second.expectedVersion)
        f.now += 30.days
        assertIs<AppResult.Success<NativePushStatus>>(f.useCase()("local"))
        assertEquals(3, f.commands.size)
    }

    @Test
    fun responseLossRecoversTheOriginalCommandAfterRecreationWithoutUndoingLaterDisable() =
        runTest {
            val f = NativePushFixture()
            f.loseResponse = true
            assertIs<AppResult.Failed>(f.useCase()("local", NativePushAction.Enable))
            val pending = assertNotNull(f.state.pending)
            val family = requireNotNull(f.session).binding.serverId
            f.remote[family] = requireNotNull(f.remote[family]).copy(enabled = false, version = 1)
            val recovered =
                assertIs<AppResult.Success<NativePushStatus>>(f.useCase()("local")).value
            assertFalse(recovered.enabled)
            assertFalse(recovered.registered)
            assertEquals(listOf(pending, pending), f.commands.map { it.second })
            assertNull(f.state.pending)
            assertNull(f.state.token)
            assertFalse(f.state.retiringToken)
            assertEquals(2, f.deletes)
            assertIs<AppResult.Success<NativePushStatus>>(f.useCase()("local"))
            assertEquals(2, f.commands.size)
        }

    @Test
    fun revokedPermissionDisablesRegistrationAndDoesNotAcquireAnotherToken() = runTest {
        val f = NativePushFixture()
        f.useCase()("local", NativePushAction.Enable)
        val acquired = f.acquisitions
        f.permission = NotificationAccess.Denied
        val status = assertIs<AppResult.Success<NativePushStatus>>(f.useCase()("local")).value
        assertFalse(status.enabled)
        assertFalse(status.registered)
        assertIs<NativePushCommand.Disable>(f.commands.last().second)
        assertEquals(acquired, f.acquisitions)
        assertEquals(2, f.deletes)
    }

    @Test
    fun deniedPermissionFailedPersistenceAndFailedRotationCannotRegisterAToken() = runTest {
        val f = NativePushFixture()
        f.permission = NotificationAccess.Denied
        assertEquals(
            FailureKind.Permission,
            assertIs<AppResult.Failed>(f.useCase()("local", NativePushAction.Enable)).failure.kind,
        )
        assertEquals(NativePushState(), f.state)
        f.permission = NotificationAccess.Granted
        f.failStorage = true
        assertIs<AppResult.Failed>(f.useCase()("local", NativePushAction.Enable))
        assertEquals(0, f.deletes)
        f.failStorage = false
        f.failDeletion = true
        assertIs<AppResult.Failed>(f.useCase()("local", NativePushAction.Enable))
        assertTrue(f.state.retiringToken)
        assertTrue(f.commands.isEmpty())
        assertEquals(0, f.acquisitions)
        f.failDeletion = false
        assertIs<AppResult.Success<NativePushStatus>>(f.useCase()("local"))
        assertEquals(1, f.commands.size)
    }

    @Test
    fun failedSdkCleanupAfterDisableRemainsPendingUntilAnExplicitRetryCompletes() = runTest {
        val f = NativePushFixture()
        f.useCase()("local", NativePushAction.Enable)
        f.failDeletion = true
        assertIs<AppResult.Failed>(f.useCase()("local", NativePushAction.Disable))
        assertTrue(f.state.retiringToken)
        assertFalse(f.state.registration!!.enabled)
        assertNull(f.state.pending)
        val sends = f.commands.size
        f.failDeletion = false
        assertIs<AppResult.Success<NativePushStatus>>(f.useCase()("local"))
        assertFalse(f.state.retiringToken)
        assertNull(f.state.token)
        assertEquals(sends, f.commands.size)
    }

    @Test
    fun accountReplacementCannotReplayTheOldCommandAndOnlyItsOriginalAccountRevokesTheFamily() =
        runTest {
            val f = NativePushFixture()
            f.loseResponse = true
            f.useCase()("local", NativePushAction.Enable)
            val original = requireNotNull(f.session)
            val pending = requireNotNull(f.state.pending)
            f.session =
                original.copy(
                    id = "replacement",
                    user = original.user.copy(id = "44444444-4444-4444-8444-444444444444"),
                    binding =
                        original.binding.copy(serverId = "55555555-5555-4555-8555-555555555555"),
                )
            assertIs<AppResult.Success<NativePushStatus>>(f.useCase()("replacement"))
            assertTrue(f.revocations.isEmpty())
            assertEquals(1, f.state.revocations.size)
            assertNotEquals(pending.operationId, f.commands.last().second.operationId)
            assertEquals(2, f.deletes)
            f.session =
                original.copy(
                    id = "returned",
                    binding =
                        original.binding.copy(serverId = "66666666-6666-4666-8666-666666666666"),
                )
            assertIs<AppResult.Success<NativePushStatus>>(f.useCase()("returned"))
            assertEquals(listOf("returned" to original.binding.serverId), f.revocations)
            assertEquals(1, f.state.revocations.size)
        }

    @Test
    fun cancellationRetainsUnconfirmedWorkAndRejectsALateTransportResult() = runTest {
        val f = NativePushFixture()
        var cancelled = false
        f.beforeSend = {
            try {
                awaitCancellation()
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        var completed = false
        val request = launch {
            f.useCase()("local", NativePushAction.Enable)
            completed = true
        }
        runCurrent()
        val pending = assertNotNull(f.state.pending)
        request.cancelAndJoin()
        assertTrue(cancelled)
        assertFalse(completed)
        assertEquals(pending, f.state.pending)
        assertNull(f.state.registration)
        f.beforeSend = {}
        assertIs<AppResult.Success<NativePushStatus>>(f.useCase()("local"))
        assertEquals(pending, f.commands.last().second)
    }

    @Test
    fun sessionReplacementWhileTransportIsPendingCannotPublishIntoTheNewOwner() = runTest {
        val f = NativePushFixture()
        val release = CompletableDeferred<Unit>()
        f.beforeSend = { release.await() }
        val request = async { f.useCase()("local", NativePushAction.Enable) }
        runCurrent()
        val prepared = f.state
        f.session = requireNotNull(f.session).copy(id = "new-session")
        release.complete(Unit)
        runCurrent()
        assertEquals(
            "client.session_changed",
            assertIs<AppResult.Failed>(request.await()).failure.code,
        )
        assertEquals(prepared, f.state)
    }

    @Test
    fun unsupportedPlatformsNeverTouchSdkOrRegistrationAndLogoutRetainsBoundedRevocation() =
        runTest {
            val f = NativePushFixture()
            assertFalse(
                assertIs<AppResult.Success<NativePushStatus>>(
                        f.useCase(AppPlatform.Web)("local", NativePushAction.Enable)
                    )
                    .value
                    .supported
            )
            assertTrue(f.commands.isEmpty())
            f.useCase()("local", NativePushAction.Enable)
            val family = f.session!!.binding.serverId
            f.session = null
            assertIs<AppResult.Success<NativePushStatus>>(f.useCase()(null))
            assertNull(f.state.owner)
            assertNull(f.state.token)
            assertEquals(family, f.state.revocations.single().familyId)
            assertTrue(f.revocations.isEmpty())
        }
}
