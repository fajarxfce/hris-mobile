@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.AppleSdkDirectoryFixture.Companion.STORE
import dev.fajar.starter.collaboration.data.datasources.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import platform.posix.*

class AppleCollaborationSdkStoreTest {
    @Test
    fun explicitAcquisitionCreatesOnlyPrivateDirectoriesAndOriginalLock() = runTest {
        AppleSdkDirectoryFixture().use { fixture ->
            val source = AppleCollaborationSdkStoreSource(fixture.root)
            source.acquire(STORE).use { lease ->
                assertFalse(lease.initiallyOccupied)
                assertEquals(STORE, lease.storeId)
                assertEquals(
                    setOf(STORE, "$STORE/owner.lock", "$STORE/database", "$STORE/cache"),
                    fixture.snapshot().keys,
                )
                for (entry in fixture.snapshot().keys) memScoped {
                    val info = alloc<stat>()
                    assertEquals(0, lstat(fixture.path(entry), info.ptr))
                    assertEquals(0, info.st_mode.toInt() and 63) // No group/other permission.
                }
                assertFailsWith<CollaborationSdkStoreBusyException> { source.acquire(STORE) }
                assertFailsWith<CollaborationSdkStoreBusyException> {
                    AppleCollaborationSdkInventorySource(fixture.root).inspect(STORE)
                }
            }
            fixture.write("$STORE/cache/retained", "existing attachment")
            val before = fixture.snapshot()
            source.acquire(STORE).use {
                assertTrue(it.initiallyOccupied)
                assertFalse(it.toString().contains(fixture.root))
                assertFalse(it.toString().contains(STORE))
            }
            assertEquals(before, fixture.snapshot())
        }
    }

    @Test
    fun missingExistingStoreNeverCreatesFilesAndUnprovisionedRootCannotBeCreated() = runTest {
        AppleSdkDirectoryFixture().use { fixture ->
            val absent = fixture.path("unprovisioned")
            assertNull(AppleCollaborationSdkStoreSource(absent).acquireExisting(STORE))
            assertNull(AppleCollaborationSdkStoreSource(fixture.root).acquireExisting(STORE))
            assertFailsWith<AppleSdkFileException> {
                AppleCollaborationSdkStoreSource(absent).acquire(STORE)
            }
            assertTrue(fixture.snapshot().isEmpty())
        }
    }

    @Test
    fun originalPairAndSidecarsStayUnderTheSameNativeAndInventoryLock() = runTest {
        AppleSdkDirectoryFixture().use { fixture ->
            fixture.seed()
            fixture.write("$STORE/database/matrix-sdk-crypto.sqlite3-wal", "original sidecar")
            fixture.write("$STORE/cache/media", "original attachment")
            val before = fixture.snapshot()
            val source = AppleCollaborationSdkStoreSource(fixture.root)
            val first = assertNotNull(source.acquireExisting(STORE))
            first.use {
                assertTrue(it.initiallyOccupied)
                assertFailsWith<CollaborationSdkStoreBusyException> { source.acquire(STORE) }
                assertFailsWith<CollaborationSdkStoreBusyException> {
                    source.acquireExisting(STORE)
                }
                assertFailsWith<CollaborationSdkStoreBusyException> {
                    AppleCollaborationSdkInventorySource(fixture.root).inspect(STORE)
                }
                AppleSdkExternalOwner(fixture.path("$STORE/owner.lock")).use {
                    assertFalse(it.tryAcquire())
                }
                source.acquire("b".repeat(64)).close()
            }
            fixture.remove("b".repeat(64))
            assertNotNull(source.acquireExisting(STORE)).use {
                first.close()
                assertFailsWith<CollaborationSdkStoreBusyException> { source.acquire(STORE) }
            }
            assertEquals(before, fixture.snapshot())
        }
    }

    @Test
    fun incompleteExistingStoreIsNotRepairedAndFailureReleasesOwnership() = runTest {
        for (entry in
            listOf(
                "owner.lock",
                "database",
                "cache",
                "database/matrix-sdk-state.sqlite3",
                "database/matrix-sdk-crypto.sqlite3",
            )) {
            AppleSdkDirectoryFixture().use { fixture ->
                fixture.seed()
                fixture.remove("$STORE/$entry")
                val before = fixture.snapshot()
                val source = AppleCollaborationSdkStoreSource(fixture.root)
                repeat(2) {
                    assertFailsWith<IllegalStateException> { source.acquireExisting(STORE) }
                }
                assertEquals(before, fixture.snapshot())
                assertFalse(fixture.exists("$STORE/$entry"))
                fixture.seed()
                assertNotNull(source.acquireExisting(STORE)).close()
            }
        }
    }

    @Test
    fun emptyDatabaseCannotBecomeAnExistingStoreAndAcquisitionNeverReplacesCiphertext() = runTest {
        AppleSdkDirectoryFixture().use { fixture ->
            fixture.seed()
            fixture.write("$STORE/database/matrix-sdk-crypto.sqlite3", "")
            val source = AppleCollaborationSdkStoreSource(fixture.root)
            val before = fixture.snapshot()
            assertFailsWith<IllegalStateException> { source.acquireExisting(STORE) }
            source.acquire(STORE).use { assertTrue(it.initiallyOccupied) }
            assertEquals(before, fixture.snapshot())
        }
    }

    @Test
    fun redirectedStoreAndSubdirectoriesAreRejectedInBothAcquisitionModes() = runTest {
        for (entry in
            listOf(
                STORE,
                "$STORE/owner.lock",
                "$STORE/database",
                "$STORE/cache",
                "$STORE/database/matrix-sdk-state.sqlite3",
                "$STORE/database/matrix-sdk-crypto.sqlite3",
                "$STORE/database/matrix-sdk-state.sqlite3-wal",
                "$STORE/database/matrix-sdk-state.sqlite3-shm",
                "$STORE/database/matrix-sdk-crypto.sqlite3-wal",
                "$STORE/database/matrix-sdk-crypto.sqlite3-shm",
            )) {
            AppleSdkDirectoryFixture().use { fixture ->
                fixture.seed()
                if (!fixture.exists(entry)) fixture.write(entry, "retained sidecar")
                AppleSdkDirectoryFixture().use { outside ->
                    outside.seed()
                    if (!outside.exists(entry)) outside.write(entry, "unrelated sidecar")
                    val before = outside.snapshot()
                    fixture.remove(entry)
                    fixture.link(entry, outside.path(entry))
                    val source = AppleCollaborationSdkStoreSource(fixture.root)
                    assertFailsWith<IllegalStateException> { source.acquireExisting(STORE) }
                    assertFailsWith<IllegalStateException> { source.acquire(STORE) }
                    assertEquals(before, outside.snapshot())
                    fixture.remove(entry)
                    fixture.seed()
                    assertNotNull(source.acquireExisting(STORE)).close()
                }
            }
        }
    }

    @Test
    fun malformedIdentifierAndObstructingCacheNeverModifyRetainedFiles() = runTest {
        AppleSdkDirectoryFixture().use { fixture ->
            val source = AppleCollaborationSdkStoreSource(fixture.root)
            for (id in listOf("../escape", "A".repeat(64))) {
                assertFailsWith<IllegalArgumentException> { source.acquire(id) }
                assertFailsWith<IllegalArgumentException> { source.acquireExisting(id) }
            }
            assertTrue(fixture.snapshot().isEmpty())
            fixture.seed()
            fixture.remove("$STORE/cache")
            fixture.write("$STORE/cache", "retained obstruction")
            val before = fixture.snapshot()
            repeat(2) { assertFailsWith<IllegalStateException> { source.acquire(STORE) } }
            assertEquals(before, fixture.snapshot())
            fixture.remove("$STORE/cache")
            fixture.directory("$STORE/cache")
            assertNotNull(source.acquireExisting(STORE)).close()
        }
    }

    @Test
    fun cancelledAcquisitionClosesTheLeaseInBothExistingAndCreationModes() = runTest {
        for (existing in listOf(false, true)) {
            AppleSdkDirectoryFixture().use { fixture ->
                if (existing) fixture.seed()
                val before = if (existing) fixture.snapshot() else null
                val scheduled = StandardTestDispatcher(testScheduler)
                lateinit var pending: Deferred<CollaborationSdkStoreLease?>
                val io =
                    object : CoroutineDispatcher() {
                        override fun dispatch(context: CoroutineContext, block: Runnable) {
                            scheduled.dispatch(
                                context,
                                Runnable {
                                    block.run()
                                    pending.cancel()
                                },
                            )
                        }
                    }
                val source = AppleCollaborationSdkStoreSource(fixture.root, io)
                pending = async {
                    if (existing) source.acquireExisting(STORE) else source.acquire(STORE)
                }
                assertFailsWith<CancellationException> { pending.await() }
                val reopened = AppleCollaborationSdkStoreSource(fixture.root)
                if (existing) {
                    assertNotNull(reopened.acquireExisting(STORE)).close()
                    assertEquals(before, fixture.snapshot())
                } else reopened.acquire(STORE).use { assertFalse(it.initiallyOccupied) }
            }
        }
    }
}
