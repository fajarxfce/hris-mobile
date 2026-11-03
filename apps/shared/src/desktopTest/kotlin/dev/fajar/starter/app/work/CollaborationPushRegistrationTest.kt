@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.CollaborationBackgroundRuntimeLease
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.push.entities.NativePushStatus
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationPushRegistrationTest {
    @Test
    fun enabledRegistrationUsesExactBackgroundScopeAndReleasesItsReference() = runTest {
        val f = Fixture()
        assertEquals(SyncResult.Complete, f.worker(f.grant))
        assertEquals(1, f.acquisitions)
        assertEquals(1, f.activations)
        assertEquals(1, f.closes)
        assertEquals(0, f.local.subscriptionCount.value)
    }

    @Test
    fun disabledUnsupportedAndDeniedNotificationsDoNotOpenAnySdkResource() = runTest {
        for (status in
            listOf(
                Fixture.enabled.copy(enabled = false),
                Fixture.enabled.copy(supported = false),
                Fixture.enabled.copy(access = NotificationAccess.Denied),
            )) {
            val f = Fixture()
            f.status = AppResult.Success(status)
            assertEquals(SyncResult.Complete, f.worker(f.grant))
            assertEquals(0, f.acquisitions)
            assertEquals(0, f.activations)
        }
    }

    @Test
    fun pendingNativeRegistrationAndFailedStatusCannotPublishAPusher() = runTest {
        for (status in
            listOf(
                AppResult.Success(Fixture.enabled.copy(pending = true)),
                AppResult.Success(Fixture.enabled.copy(registered = false)),
                AppResult.Failed(Fixture.denied),
            )) {
            val f = Fixture()
            f.status = status
            assertNotEquals(SyncResult.Complete, f.worker(f.grant))
            assertEquals(0, f.acquisitions)
        }
    }

    @Test
    fun preferenceChangeDuringBorrowClosesWithoutActivation() = runTest {
        val f = Fixture()
        f.beforeAcquire = { f.status = AppResult.Success(Fixture.enabled.copy(enabled = false)) }
        assertEquals(SyncResult.Complete, f.worker(f.grant))
        assertEquals(0, f.activations)
        assertEquals(1, f.closes)
    }

    @Test
    fun lateWrongOwnerAndFailedCloseCannotBeAcknowledgedAsComplete() = runTest {
        val f = Fixture()
        f.leaseOwner = f.owner.copy(nativeSessionId = "replacement")
        assertIs<SyncResult.Blocked>(f.worker(f.grant))
        assertEquals(1, f.closes)
        assertEquals(0, f.activations)
        f.leaseOwner = f.owner
        f.closeResult = AppResult.Failed(Failure(FailureKind.Storage, "Fictional close failure"))
        assertIs<SyncResult.Blocked>(f.worker(f.grant))
        assertEquals(2, f.closes)
    }

    @Test
    fun credentialRevocationCancelsAndJoinsProviderBeforeClosingItsReference() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        f.send = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    delay(1.seconds)
                    events += "drained"
                }
            }
        }
        f.onClose = { events += "closed" }
        val job = async { f.worker(f.grant) }
        entered.await()
        f.credentials.value = Fixture.denied
        assertIs<SyncResult.Blocked>(job.await())
        assertEquals(listOf("drained", "closed"), events)
        assertEquals(0, f.local.subscriptionCount.value)
    }

    @Test
    fun externalCancellationClosesTheBorrowAndReleasesTheExecutionSlot() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        f.send = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.worker(f.grant) }
        entered.await()
        assertIs<SyncResult.Retry>(f.worker(f.grant))
        assertEquals(1, f.acquisitions)
        job.cancelAndJoin()
        assertEquals(1, f.closes)
        f.send = { AppResult.Success(Unit) }
        assertEquals(SyncResult.Complete, f.worker(f.grant))
        assertEquals(2, f.closes)
    }

    @Test
    fun stalledRegistrationHasAFiniteDeadlineAndStillCloses() = runTest {
        val f = Fixture()
        f.send = { awaitCancellation() }
        assertIs<SyncResult.Retry>(f.worker(f.grant))
        assertEquals(15_000L, testScheduler.currentTime)
        assertEquals(1, f.closes)
    }

    private class Fixture {
        val now = Clock.System.now()
        val owner =
            CollaborationOwner("local", "https://hris.example.test", "account", "company", "native")
        val grant =
            CollaborationBackgroundAdmission(
                CollaborationAdmissionObservation(owner, "device", 1, 0, 0, now, now + 60.seconds),
                now,
                now + 60.seconds,
            )
        val local =
            MutableStateFlow<AppResult<CollaborationLocalAccess>>(
                AppResult.Success(owner.localListAccess())
            )
        val credentials = MutableStateFlow<Failure?>(null)
        var leaseOwner = owner
        var status: AppResult<NativePushStatus> = AppResult.Success(enabled)
        var acquisitions = 0
        var activations = 0
        var closes = 0
        var beforeAcquire: suspend () -> Unit = {}
        var onClose: suspend () -> Unit = {}
        var closeResult: AppResult<Unit> = AppResult.Success(Unit)
        var send: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
        val lease =
            object : CollaborationBackgroundRuntimeLease {
                override val owner
                    get() = leaseOwner

                override val deviceId = "device"
                override val credentialFailures = credentials
                override val interruptions = MutableStateFlow<Failure?>(null)

                override fun publish(): AppResult<Unit> = error("Only acquisition publishes")

                override suspend fun close(): AppResult<Unit> {
                    closes++
                    onClose()
                    return closeResult
                }
            }
        val worker =
            CollaborationPushRegistration(
                { session ->
                    assertEquals(owner.sessionId, session)
                    flowOf(status)
                },
                { admission ->
                    assertSame(grant, admission)
                    acquisitions++
                    beforeAcquire()
                    AppResult.Success(lease)
                },
                { expected ->
                    assertEquals(owner.localListAccess(), expected)
                    local
                },
                { scope, device ->
                    assertSame(
                        grant,
                        assertIs<CollaborationOperationScope.Background>(scope).admission,
                    )
                    assertEquals("device", device)
                    activations++
                    send()
                },
            )

        companion object {
            val enabled =
                NativePushStatus(
                    true,
                    true,
                    true,
                    false,
                    NotificationAccess.Granted,
                    Clock.System.now() + 600.seconds,
                )
            val denied =
                Failure(FailureKind.AccessDenied, "Fictional revocation", code = "mfa_required")
        }
    }
}
