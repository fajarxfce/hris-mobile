@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationSdkClientSource
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

/** Real resource owner with inert FFI handles; provider delivery is a separate native gate. */
class AndroidCollaborationReadinessLifetimeTest {
    @Test
    fun registrationIsInstalledOnceAndReleasedBeforeClientAndDirectory() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        try {
            repeat(3) {
                f.lease.startReadinessObservation(AndroidCollaborationSdkClientSource()) {
                    error("No packets")
                }
            }
            assertEquals(listOf("readiness.subscribe"), f.calls)
            f.lease.finishAcquisition()
            assertEquals(AppResult.Success(Unit), f.lease.close())
            assertEquals(1, f.calls.count { it == "readiness.cancel" })
            assertEquals(1, f.calls.count { it == "readiness.close" })
            assertTrue(f.calls.indexOf("readiness.close") < f.calls.indexOf("client.close"))
            assertTrue(f.calls.indexOf("client.close") < f.calls.indexOf("directory.close"))
        } finally {
            f.lease.finishAcquisition()
            f.close()
        }
    }

    @Test
    fun closeDuringSubscriptionReleasesReturnedHandleAndNeverSubscribesAgain() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        try {
            f.beforeReadinessSubscribe = { f.lease.requestClose() }
            f.lease.startReadinessObservation(AndroidCollaborationSdkClientSource()) {
                error("Closed owner")
            }
            assertEquals(
                listOf("readiness.subscribe", "readiness.cancel", "readiness.close"),
                f.calls,
            )
            f.lease.startReadinessObservation(AndroidCollaborationSdkClientSource()) {
                error("No reopen")
            }
            f.lease.finishAcquisition()
            assertEquals(AppResult.Success(Unit), f.lease.close())
            assertEquals(1, f.calls.count { it == "readiness.close" })
        } finally {
            f.lease.finishAcquisition()
            f.close()
        }
    }

    @Test
    fun failedSubscriptionAndPendingAcquisitionStillRetainExclusiveDirectoryUntilDrain() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        try {
            f.failReadinessSubscribe = true
            assertFailsWith<IllegalStateException> {
                f.lease.startReadinessObservation(AndroidCollaborationSdkClientSource()) {
                    error("No packets")
                }
            }
            val closing = async { f.lease.close() }
            runCurrent()
            assertFalse(closing.isCompleted)
            assertFalse("directory.close" in f.calls)
            f.lease.finishAcquisition()
            runCurrent()
            assertEquals(AppResult.Success(Unit), closing.await())
            assertEquals(0, f.calls.count { it == "readiness.close" })
            assertTrue("client.close" in f.calls)
            assertTrue("directory.close" in f.calls)
        } finally {
            f.lease.finishAcquisition()
            f.close()
        }
    }
}
