package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.coroutines.*
import org.matrix.rustcomponents.sdk.ExistingCryptoIdentity
import org.matrix.rustcomponents.sdk.ExistingCryptoIdentityException

class AndroidSdkPrivateIdentitySourceTest {
    @Test
    fun cancellationCannotReleaseTheDirectoryWhileTheSynchronousNativeReadIsRunning() =
        runBlocking {
            val root = Files.createTempDirectory("sdk-private-read").toFile()
            val stores = FileCollaborationSdkStoreSource(root)
            val id = "a".repeat(64)
            stores.acquire(id).close()
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val nativeFinished = AtomicBoolean()
            val leaseClosed = AtomicBoolean()
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val source =
                    AndroidCollaborationSdkPrivateIdentitySource(dispatcher) { path, passphrase ->
                        check(
                            path == File(root, "$id/database").absolutePath &&
                                passphrase == "fixture"
                        )
                        entered.complete(Unit)
                        check(release.await(10, TimeUnit.SECONDS))
                        nativeFinished.set(true)
                        ExistingCryptoIdentity("@local:proof.invalid", "LOCAL", "signing", "curve")
                    }
                val pending = async {
                    val lease = checkNotNull(FileCollaborationSdkInventorySource(root).inspect(id))
                    try {
                        source.read(lease.databasePath, "fixture")
                    } finally {
                        check(nativeFinished.get())
                        lease.close()
                        leaseClosed.set(true)
                    }
                }
                try {
                    withTimeout(5_000) { entered.await() }
                    pending.cancel()
                    delay(25)
                    assertFalse(pending.isCompleted)
                    assertFalse(leaseClosed.get())
                    assertFailsWith<CollaborationSdkStoreBusyException> {
                        stores.acquire(id).close()
                    }
                    release.countDown()
                    assertFailsWith<CancellationException> {
                        withTimeout(5_000) { pending.await() }
                    }
                    assertTrue(nativeFinished.get() && leaseClosed.get())
                    stores.acquire(id).close()
                } finally {
                    release.countDown()
                    pending.cancelAndJoin()
                    check(root.deleteRecursively())
                }
            }
        }

    @Test
    fun rawNativeFailureRetainsItsTypeAndDoesNotBecomeAnEmptyIdentity() = runBlocking {
        val failure = ExistingCryptoIdentityException.InvalidStore()
        val source =
            AndroidCollaborationSdkPrivateIdentitySource(nativeRead = { _, _ -> throw failure })
        val received =
            assertFailsWith<ExistingCryptoIdentityException.InvalidStore> {
                source.read("/fixture", "wrong")
            }
        // Coroutine stack-trace recovery may copy the exception with the original as its cause.
        assertSame(failure, received.cause ?: received)
    }
}
