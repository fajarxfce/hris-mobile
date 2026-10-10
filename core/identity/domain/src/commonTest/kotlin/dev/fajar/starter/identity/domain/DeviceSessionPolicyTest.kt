package dev.fajar.starter.identity.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.identity.domain.usecases.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class DeviceSessionPolicyTest {
    private val original = testSession()
    private val server = IdentityServer(original.binding.audience, "Test device")
    private val otherId = "22222222-2222-4222-8222-222222222222"

    private class Devices : DeviceSessionRepository {
        var listCalls = 0
        val revoked = mutableListOf<Pair<String, String>>()
        var beforeList: suspend () -> Unit = {}
        var beforeRevoke: suspend () -> Unit = {}
        var failure: AppResult.Failed? = null

        override suspend fun list(sessionId: String): AppResult<List<DeviceSession>> {
            listCalls++
            beforeList()
            return failure ?: AppResult.Success(emptyList())
        }

        override suspend fun revoke(sessionId: String, remoteId: String): AppResult<Unit> {
            revoked += sessionId to remoteId
            beforeRevoke()
            return failure ?: AppResult.Success(Unit)
        }
    }

    @Test
    fun listingUsesTheServerFamilyAndRejectsAChangedOwnerAfterTheResponse() = runTest {
        val sessions = TestSessions(original)
        val devices = Devices()
        val load = LoadDeviceSessions(sessions, devices)
        assertEquals(
            original.binding.serverId,
            assertIs<AppResult.Success<DeviceSessions>>(load(original.id)).value.currentId,
        )
        devices.beforeList = { sessions.compareAndSet(original, original.copy(id = "replacement")) }
        assertEquals(
            "client.session_changed",
            assertIs<AppResult.Failed>(load(original.id)).failure.code,
        )
        assertIs<AppResult.Failed>(load(original.id))
        assertEquals(2, devices.listCalls)
    }

    @Test
    fun currentDeviceCannotBeRevokedThroughTheOtherSessionsAction() = runTest {
        val devices = Devices()
        val revoke = RevokeDeviceSession(TestSessions(original), devices)
        assertEquals(
            "client.current_session",
            assertIs<AppResult.Failed>(revoke(original.id, original.binding.serverId)).failure.code,
        )
        assertEquals(AppResult.Success(Unit), revoke(original.id, otherId))
        assertEquals(listOf(original.id to otherId), devices.revoked)
    }

    @Test
    fun logoutClearsRotatedCredentialsAndReportsConfirmedServerRevocation() = runTest {
        val sessions = TestSessions(original)
        val devices = Devices()
        devices.beforeRevoke = {
            sessions.compareAndSet(
                original,
                original.copy(tokens = original.tokens.copy(accessToken = "rotated")),
            )
        }
        val result =
            assertIs<AppResult.Success<SignOutReceipt>>(
                SignOutNativeSession(sessions, devices, server)(original.id)
            )
        assertTrue(result.value.remoteRevoked)
        assertNull(result.value.remoteFailure)
        assertEquals(listOf(original.id to original.binding.serverId), devices.revoked)
        assertEquals(AppResult.Success(null), sessions.current())
        assertEquals(AppResult.Success(false), sessions.compareAndSet(original, original))
    }

    @Test
    fun aLostRemoteResponseStillClearsLocalCredentialsWithoutClaimingRevocation() = runTest {
        val sessions = TestSessions(original)
        val devices =
            Devices().apply { failure = AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
        val receipt =
            assertIs<AppResult.Success<SignOutReceipt>>(
                    SignOutNativeSession(sessions, devices, server)(original.id)
                )
                .value
        assertFalse(receipt.remoteRevoked)
        assertSame(devices.failure?.failure, receipt.remoteFailure)
        assertEquals(AppResult.Success(null), sessions.current())
    }

    @Test
    fun disposalWhileRevocationIsPendingFinishesLocalLogoutAndPropagatesCancellation() = runTest {
        val sessions = TestSessions(original)
        val devices = Devices()
        val started = CompletableDeferred<Unit>()
        devices.beforeRevoke = {
            started.complete(Unit)
            awaitCancellation()
        }
        val request = async { SignOutNativeSession(sessions, devices, server)(original.id) }
        started.await()
        request.cancel()
        assertFailsWith<CancellationException> { request.await() }
        assertEquals(AppResult.Success(null), sessions.current())
    }

    @Test
    fun logoutAndLateListCannotDeleteOrPublishIntoAReplacementAccount() = runTest {
        val sessions = TestSessions(original)
        val replacement =
            original.copy(id = "replacement", user = original.user.copy(id = "another"))
        val devices = Devices()
        devices.beforeRevoke = { sessions.compareAndSet(original, replacement) }
        assertIs<AppResult.Success<SignOutReceipt>>(
            SignOutNativeSession(sessions, devices, server)(original.id)
        )
        assertEquals(AppResult.Success(replacement), sessions.current())
        SignOutNativeSession(sessions, devices, server)(original.id)
        assertEquals(1, devices.revoked.size)
    }

    @Test
    fun failedLocalDeletionRemainsAFailureEvenWhenTheServerRevokedTheFamily() = runTest {
        val storageFailure = AppResult.Failed(Failure(FailureKind.Storage, "Storage unavailable"))
        val sessions =
            object : SessionRepository by TestSessions(original) {
                override suspend fun clear(sessionId: String): AppResult<Boolean> = storageFailure
            }
        assertSame(storageFailure, SignOutNativeSession(sessions, Devices(), server)(original.id))
    }

    @Test
    fun anAudienceChangeDoesNotSendCredentialsToTheNewServer() = runTest {
        val sessions = TestSessions(original)
        val devices = Devices()
        val result =
            assertIs<AppResult.Success<SignOutReceipt>>(
                SignOutNativeSession(
                    sessions,
                    devices,
                    IdentityServer("https://other.invalid/", "Device"),
                )(original.id)
            )
        assertFalse(result.value.remoteRevoked)
        assertEquals(0, devices.revoked.size)
        assertEquals(AppResult.Success(null), sessions.current())
    }

    @Test
    fun aLateNonCooperativeListIsCancelledBeforeItCanPublish() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val devices =
            Devices().apply {
                beforeList = {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
        val request = async { LoadDeviceSessions(TestSessions(original), devices)(original.id) }
        started.await()
        request.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { request.await() }
    }
}
