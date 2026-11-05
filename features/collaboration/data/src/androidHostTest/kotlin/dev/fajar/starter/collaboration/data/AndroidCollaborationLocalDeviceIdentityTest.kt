package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationSdkClientSource
import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalDeviceIdentity
import dev.fajar.starter.common.result.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*

/** Real resource ownership with inert FFI handles; no provider/JNI or private-key proof claim. */
class AndroidCollaborationLocalDeviceIdentityTest {
    private val source = AndroidCollaborationSdkClientSource()

    @Test
    fun publicIdentityComesFromOwnedSdkKeysWithoutStartingNetworkOrStoppingItsClient() =
        runBlocking {
            val f = runtimeLeaseFixture()
            try {
                f.lease.completeAcquisition()
                assertEquals(AppResult.Success(Unit), f.lease.publish())
                repeat(2) {
                    val value =
                        assertIs<AppResult.Success<CollaborationLocalDeviceIdentity>>(
                                f.lease.localDeviceIdentity(source)
                            )
                            .value
                    assertEquals("@native:proof.invalid", value.matrixUserId)
                    assertEquals("PROOF_NATIVE", value.matrixDeviceId)
                    assertEquals(
                        "5a82e4a4e4d17683c4354f1947220fa73f65383750bbbd59e041c0ad772621fc",
                        value.fingerprint,
                    )
                    assertFalse(value.toString().contains(value.matrixUserId))
                    assertTrue(f.lease.availableForEntry)
                }
                assertEquals(listOf("encryption.close", "encryption.close"), f.calls.toList())
                f.calls.clear()
            } finally {
                f.close()
            }
            f.assertReleased()
        }

    @Test
    fun unpublishedMalformedAndCredentialFailedClientsCannotSupplyUsableKeyIdentity() =
        runBlocking {
            val f = runtimeLeaseFixture()
            try {
                assertIs<AppResult.Failed>(f.lease.localDeviceIdentity(source))
                f.lease.completeAcquisition()
                assertIs<AppResult.Failed>(f.lease.localDeviceIdentity(source))
                assertTrue(f.calls.isEmpty())
                assertEquals(AppResult.Success(Unit), f.lease.publish())
                f.signingKey = null
                assertIs<AppResult.Failed>(f.lease.localDeviceIdentity(source))
                assertEquals(listOf("encryption.close"), f.calls.toList())
                f.persistence.reportCallbackFailure()
                val failure = assertNotNull(f.lease.credentialFailures.value)
                assertEquals(AppResult.Failed(failure), f.lease.localDeviceIdentity(source))
                assertEquals(listOf("encryption.close"), f.calls.toList())
                f.calls.clear()
            } finally {
                f.close()
            }
            f.assertReleased()
        }

    @Test
    fun cancelledPendingNativeObservationKeepsTheDirectoryUntilTheGetterReturns() = runBlocking {
        val f = runtimeLeaseFixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.beforeIdentity = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        try {
            f.lease.completeAcquisition()
            assertEquals(AppResult.Success(Unit), f.lease.publish())
            val read = async(Dispatchers.Default) { f.lease.localDeviceIdentity(source) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            read.cancel()
            val closing = async { f.lease.close() }
            yield()
            assertTrue(f.lease.closingRequested)
            assertFalse(closing.isCompleted)
            assertFalse("directory.close" in f.calls)
            assertFalse("client.close" in f.calls)
            release.countDown()
            withTimeout(5_000) { read.join() }
            assertTrue(read.isCancelled)
            assertEquals(AppResult.Success(Unit), withTimeout(5_000) { closing.await() })
            assertEquals(1, f.calls.count { it == "directory.close" })
            assertEquals(1, f.calls.count { it == "client.close" })
            assertEquals(1, f.calls.count { it == "released" })
            assertEquals(2, f.calls.count { it == "encryption.close" })
        } finally {
            release.countDown()
            f.close()
        }
    }

    @Test
    fun credentialFailureArrivingDuringNativeObservationWinsOverItsPublicKeys() = runBlocking {
        val f = runtimeLeaseFixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.beforeIdentity = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        try {
            f.lease.completeAcquisition()
            assertEquals(AppResult.Success(Unit), f.lease.publish())
            val read = async(Dispatchers.Default) { f.lease.localDeviceIdentity(source) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            f.persistence.reportCallbackFailure()
            val failure = assertNotNull(f.lease.credentialFailures.value)
            release.countDown()
            assertEquals(AppResult.Failed(failure), withTimeout(5_000) { read.await() })
            assertFalse("directory.close" in f.calls)
            assertEquals(listOf("encryption.close"), f.calls.toList())
            f.calls.clear()
        } finally {
            release.countDown()
            f.close()
        }
        f.assertReleased()
    }
}
