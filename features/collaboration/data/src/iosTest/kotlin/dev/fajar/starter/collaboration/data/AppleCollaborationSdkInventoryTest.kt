@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.AppleSdkDirectoryFixture.Companion.STORE
import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.repositories.StoredCollaborationSdkInventoryRepository
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.AtomicCredentialStore
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import platform.posix.mkfifo

class AppleCollaborationSdkInventoryTest {
    @Test
    fun absentRootAndStoreNeverCreateFiles() = runTest {
        AppleSdkDirectoryFixture().use { fixture ->
            assertNull(AppleCollaborationSdkInventorySource(fixture.path("absent")).inspect(STORE))
            assertFalse(fixture.exists("absent"))
            assertNull(AppleCollaborationSdkInventorySource(fixture.root).inspect(STORE))
            assertTrue(fixture.snapshot().isEmpty())
        }
    }

    @Test
    fun invalidNamesFailBeforeAnyFilesystemMutation() = runTest {
        AppleSdkDirectoryFixture().use { fixture ->
            for (id in listOf("../escape", "A".repeat(64), "a".repeat(63), STORE + "\u0000")) {
                assertFailsWith<IllegalArgumentException> {
                    AppleCollaborationSdkInventorySource(fixture.root).inspect(id)
                }
            }
            for (root in listOf("relative", fixture.root + "\u0000suffix")) {
                assertFailsWith<IllegalArgumentException> {
                    AppleCollaborationSdkInventorySource(root).inspect(STORE)
                }
            }
            assertTrue(fixture.snapshot().isEmpty())
        }
    }

    @Test
    fun missingOriginalLockIsNotRepaired() = runTest {
        AppleSdkDirectoryFixture().use { fixture ->
            fixture.directory(STORE)
            val before = fixture.snapshot()
            repeat(2) {
                assertFailsWith<AppleSdkFileException> {
                    AppleCollaborationSdkInventorySource(fixture.root).inspect(STORE)
                }
            }
            assertEquals(before, fixture.snapshot())
        }
    }

    @Test
    fun cacheAndEmptyFilesCannotReplaceTheOriginalDatabasePair() = runTest {
        AppleSdkDirectoryFixture().use { fixture ->
            fixture.seed()
            fixture.write("$STORE/database/matrix-sdk-state.sqlite3", "")
            fixture.remove("$STORE/database/matrix-sdk-crypto.sqlite3")
            fixture.write("$STORE/cache/matrix-sdk-crypto.sqlite3", "cache is not identity")
            val before = fixture.snapshot()
            assertNotNull(AppleCollaborationSdkInventorySource(fixture.root).inspect(STORE)).use {
                assertFalse(it.stateDatabasePresent)
                assertFalse(it.cryptoDatabasePresent)
            }
            assertEquals(before, fixture.snapshot())
        }
    }

    @Test
    fun absentDatabaseDirectoryReportsFileFactsWithoutRepair() = runTest {
        AppleSdkDirectoryFixture().use { fixture ->
            fixture.directory(STORE)
            fixture.write("$STORE/owner.lock", "")
            val before = fixture.snapshot()
            assertNotNull(AppleCollaborationSdkInventorySource(fixture.root).inspect(STORE)).use {
                assertFalse(it.stateDatabasePresent)
                assertFalse(it.cryptoDatabasePresent)
            }
            assertEquals(before, fixture.snapshot())
        }
    }

    @Test
    fun originalPairAndSidecarsRemainUnchangedThroughIndependentOwners() = runTest {
        AppleSdkDirectoryFixture().use { fixture ->
            fixture.seed()
            fixture.seed("b".repeat(64))
            fixture.write("$STORE/database/matrix-sdk-crypto.sqlite3-wal", "retained sidecar")
            val before = fixture.snapshot()
            val source = AppleCollaborationSdkInventorySource(fixture.root)
            val first = assertNotNull(source.inspect(STORE))
            assertTrue(first.stateDatabasePresent && first.cryptoDatabasePresent)
            assertFalse(first.toString().contains(STORE))
            assertFalse(first.toString().contains(fixture.root))
            first.use {
                assertFailsWith<CollaborationSdkStoreBusyException> { source.inspect(STORE) }
                assertFailsWith<CollaborationSdkStoreBusyException> {
                    AppleCollaborationSdkInventorySource(fixture.root).inspect(STORE)
                }
                assertNotNull(source.inspect("b".repeat(64))).close()
            }
            assertNotNull(source.inspect(STORE)).use {
                first.close() // An old lease cannot release the replacement's native descriptor.
                assertFailsWith<CollaborationSdkStoreBusyException> { source.inspect(STORE) }
            }
            assertEquals(before, fixture.snapshot())
        }
    }

    @Test
    fun separateNativeLockOwnerExcludesInventoryInBothDirections() = runTest {
        AppleSdkDirectoryFixture().use { fixture ->
            fixture.seed()
            val source = AppleCollaborationSdkInventorySource(fixture.root)
            AppleSdkExternalOwner(fixture.path("$STORE/owner.lock")).use { native ->
                assertTrue(native.tryAcquire())
                assertFailsWith<CollaborationSdkStoreBusyException> { source.inspect(STORE) }
            }
            val lease = assertNotNull(source.inspect(STORE))
            AppleSdkExternalOwner(fixture.path("$STORE/owner.lock")).use { native ->
                lease.use { assertFalse(native.tryAcquire()) }
                assertTrue(native.tryAcquire())
            }
        }
    }

    @Test
    fun trustedRootAliasReturnsTheCanonicalOriginalDirectory() = runTest {
        AppleSdkDirectoryFixture().use { fixture ->
            fixture.seed()
            AppleSdkDirectoryFixture().use { aliases ->
                aliases.link("root", fixture.root)
                val direct = AppleCollaborationSdkInventorySource(fixture.root)
                val path = assertNotNull(direct.inspect(STORE)).use { it.databasePath }
                assertNotNull(
                        AppleCollaborationSdkInventorySource(aliases.path("root")).inspect(STORE)
                    )
                    .use {
                        assertEquals(path, it.databasePath)
                        assertFailsWith<CollaborationSdkStoreBusyException> {
                            direct.inspect(STORE)
                        }
                    }
            }
        }
    }

    @Test
    fun redirectedStoreLockDatabaseAndEntriesNeverTouchTheirDestination() = runTest {
        for (relative in
            listOf(
                STORE,
                "$STORE/owner.lock",
                "$STORE/database",
                "$STORE/database/matrix-sdk-crypto.sqlite3",
            )) {
            AppleSdkDirectoryFixture().use { fixture ->
                fixture.seed()
                AppleSdkDirectoryFixture().use { outside ->
                    outside.seed()
                    val before = outside.snapshot()
                    fixture.remove(relative)
                    fixture.link(relative, outside.path(relative))
                    assertFailsWith<IllegalStateException> {
                        AppleCollaborationSdkInventorySource(fixture.root).inspect(STORE)
                    }
                    assertEquals(before, outside.snapshot())
                    fixture.remove(relative)
                    if (relative == STORE || relative == "$STORE/database")
                        fixture.directory(relative)
                    fixture.seed()
                    assertNotNull(AppleCollaborationSdkInventorySource(fixture.root).inspect(STORE))
                        .close()
                }
            }
        }
    }

    @Test
    fun nonFileObstructionsFailAndReleaseAcquiredLocks() = runTest {
        for (relative in
            listOf(
                "$STORE/owner.lock",
                "$STORE/database/matrix-sdk-state.sqlite3",
                "$STORE/database/matrix-sdk-crypto.sqlite3",
            )) {
            AppleSdkDirectoryFixture().use { fixture ->
                fixture.seed()
                fixture.remove(relative)
                fixture.directory(relative)
                val before = fixture.snapshot()
                repeat(2) {
                    assertFailsWith<IllegalStateException> {
                        AppleCollaborationSdkInventorySource(fixture.root).inspect(STORE)
                    }
                }
                assertEquals(before, fixture.snapshot())
                fixture.remove(relative)
                fixture.seed()
                assertNotNull(AppleCollaborationSdkInventorySource(fixture.root).inspect(STORE))
                    .close()
            }
        }
    }

    @Test
    fun fifoLockCannotBlockInventoryOrBecomeAnOwnerFile() = runTest {
        AppleSdkDirectoryFixture().use { fixture ->
            fixture.seed()
            fixture.remove("$STORE/owner.lock")
            assertEquals(0, mkfifo(fixture.path("$STORE/owner.lock"), 384u))
            assertFailsWith<IllegalStateException> {
                AppleCollaborationSdkInventorySource(fixture.root).inspect(STORE)
            }
            fixture.remove("$STORE/owner.lock")
            fixture.write("$STORE/owner.lock", "")
            assertNotNull(AppleCollaborationSdkInventorySource(fixture.root).inspect(STORE)).close()
        }
    }

    @Test
    fun cancellationAtDispatcherReturnReleasesTheOriginalLease() = runTest {
        AppleSdkDirectoryFixture().use { fixture ->
            fixture.seed()
            val before = fixture.snapshot()
            val scheduled = StandardTestDispatcher(testScheduler)
            lateinit var pending: Deferred<CollaborationSdkInventoryLease?>
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
            pending = async {
                AppleCollaborationSdkInventorySource(fixture.root, io).inspect(STORE)
            }
            assertFailsWith<CancellationException> { pending.await() }
            assertNotNull(AppleCollaborationSdkInventorySource(fixture.root).inspect(STORE)).close()
            assertEquals(before, fixture.snapshot())
        }
    }

    @Test
    fun sharedRepositoryRetainsTheNativeLockThroughoutProtectedReadingAndCancellation() = runTest {
        AppleSdkDirectoryFixture().use { fixture ->
            val server = IdentityServer(SETUP_BASE, "Fixture")
            val endpoint = CollaborationSdkEndpoint("https://hris.example.test/")
            val scope = collaborationSdkScope(setupOwner, SETUP_DEVICE, server, endpoint)
            fixture.seed(scope.storeId)
            val before = fixture.snapshot()
            var closed = 0
            var cancelRead = false
            val entered = CompletableDeferred<Unit>()
            val source = AppleCollaborationSdkInventorySource(fixture.root)
            val repository =
                StoredCollaborationSdkInventoryRepository(
                    source,
                    { name ->
                        assertEquals(scope.credentialRecordName, name)
                        object : AtomicCredentialStore {
                            override val persistent = true

                            override suspend fun read(): String? {
                                AppleSdkExternalOwner(fixture.path("${scope.storeId}/owner.lock"))
                                    .use { assertFalse(it.tryAcquire()) }
                                if (cancelRead) {
                                    entered.complete(Unit)
                                    awaitCancellation()
                                }
                                return null
                            }

                            override suspend fun write(value: String?) =
                                error("Unexpected credential write")

                            override suspend fun writeIfAbsent(value: String): String =
                                error("Unexpected enrollment")

                            override fun close() {
                                closed++
                            }
                        }
                    },
                    server,
                    endpoint,
                )
            assertIs<AppResult.Success<*>>(repository.inspect(setupOwner, SETUP_DEVICE))
            assertEquals(1, closed)
            cancelRead = true
            val pending = async { repository.inspect(setupOwner, SETUP_DEVICE) }
            entered.await()
            pending.cancel()
            assertFailsWith<CancellationException> { pending.await() }
            assertEquals(2, closed)
            assertNotNull(source.inspect(scope.storeId)).close()
            assertEquals(before, fixture.snapshot())
        }
    }
}
