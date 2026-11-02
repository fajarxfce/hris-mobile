@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationBackgroundRuntimeLease
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationBackgroundDeliveryMonitorTest {
    @Test
    fun anAlreadyFailedReferenceNeverStartsDeliveryOrWaitsForIdentity() = runTest {
        val f = Fixture()
        val failure = Failure(FailureKind.Storage, "Credential write failed")
        f.credentials.value = failure
        val result =
            monitorCollaborationBackgroundDelivery(
                f.lease,
                flow { awaitCancellation() },
                f.access,
            ) {
                error("No delivery after credential failure")
            }
        assertEquals(SyncResult.Blocked(failure), result)
        assertEquals(0, f.closes)
        assertEquals(0, f.credentials.subscriptionCount.value)
    }

    @Test
    fun sdkFailureStopsPendingDeliveryEvenBeforeTheFirstIdentityEmission() = runTest {
        for (credential in listOf(false, true)) {
            val f = Fixture()
            var released = false
            val task = async {
                monitorCollaborationBackgroundDelivery(
                    f.lease,
                    flow { awaitCancellation() },
                    f.access,
                ) {
                    try {
                        awaitCancellation()
                    } finally {
                        released = true
                    }
                }
            }
            runCurrent()
            val failure = Failure(FailureKind.Network, "Disconnected")
            if (credential) f.credentials.value = failure else f.interruptions.value = failure
            runCurrent()
            assertEquals(SyncResult.Retry(failure), task.await())
            assertTrue(released)
            assertEquals(0, f.closes)
        }
    }

    @Test
    fun companyDenialOrOwnerReplacementCancelsTheOwnedPendingChild() = runTest {
        for (replace in listOf(false, true)) {
            val f = Fixture()
            val owner =
                MutableStateFlow<AppResult<CollaborationLocalAccess>>(AppResult.Success(f.access))
            var released = false
            val task = async {
                monitorCollaborationBackgroundDelivery(f.lease, owner, f.access) {
                    try {
                        awaitCancellation()
                    } finally {
                        released = true
                    }
                }
            }
            runCurrent()
            owner.value =
                if (replace) AppResult.Success(f.access.copy(sessionId = "replacement"))
                else
                    AppResult.Failed(
                        Failure(FailureKind.AccessDenied, "Access denied", code = "mfa_required")
                    )
            runCurrent()
            assertIs<SyncResult.Blocked>(task.await())
            assertTrue(released)
            assertEquals(0, f.closes)
        }
    }

    @Test
    fun successfulCompletionDisposesAllObserversAndLeavesLeaseReleaseToItsCaller() = runTest {
        val f = Fixture()
        var observers = 0
        val owner = flow {
            observers++
            try {
                emit(AppResult.Success(f.access))
                awaitCancellation()
            } finally {
                observers--
            }
        }
        val task = async {
            monitorCollaborationBackgroundDelivery(f.lease, owner, f.access) {
                yield()
                SyncResult.Complete
            }
        }
        runCurrent()
        assertEquals(SyncResult.Complete, task.await())
        assertEquals(0, observers)
        assertEquals(0, f.closes)
        assertEquals(0, f.credentials.subscriptionCount.value)
        assertEquals(0, f.interruptions.subscriptionCount.value)
    }

    @Test
    fun callerCancellationDisposesObserversAndCancelsItsPendingChild() = runTest {
        val f = Fixture()
        var released = false
        val task = async {
            monitorCollaborationBackgroundDelivery(
                f.lease,
                flowOf(AppResult.Success(f.access)),
                f.access,
            ) {
                try {
                    awaitCancellation()
                } finally {
                    released = true
                }
            }
        }
        runCurrent()
        task.cancelAndJoin()
        assertTrue(released)
        assertEquals(0, f.credentials.subscriptionCount.value)
        assertEquals(0, f.interruptions.subscriptionCount.value)
        assertEquals(0, f.closes)
    }

    private class Fixture {
        val access =
            CollaborationLocalAccess("local", "https://hris.example.test/", "account", "company")
        val credentials = MutableStateFlow<Failure?>(null)
        val interruptions = MutableStateFlow<Failure?>(null)
        var closes = 0
        val lease =
            object : CollaborationBackgroundRuntimeLease {
                override val owner =
                    CollaborationOwner(
                        access.sessionId,
                        access.audience,
                        access.accountId,
                        access.companyId,
                        "native",
                    )
                override val deviceId = "device"
                override val credentialFailures = credentials
                override val interruptions = this@Fixture.interruptions

                override fun publish(): AppResult<Unit> = error("Monitor cannot publish")

                override suspend fun close(): AppResult<Unit> {
                    closes++
                    return AppResult.Success(Unit)
                }
            }
    }
}
