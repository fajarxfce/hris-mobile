package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.mappers.toLocalDeviceIdentity
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRuntimeLease
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AndroidSpaceRuntimeAdoptionTest {
    @Test
    fun installedSpaceObservesItsOriginalSourceWithoutAnotherClientOrTransportRead() = runBlocking {
        lateinit var native: SpaceRuntimeSyncClientSource
        val f =
            SpaceAdoptionStorageFixture.create { identity ->
                SpaceRuntimeSyncClientSource(identity).also { native = it }
            }
        try {
            f.select()
            val lease =
                assertIs<AppResult.Success<CollaborationSpaceRuntimeLease>>(f.install()).value
            val runtime =
                CollaborationSpaceRuntimeReady(
                    lease.owner,
                    lease.deviceId,
                    lease.installedOperationId,
                )
            assertNull(f.repo.incomingSource(runtime))
            assertEquals(AppResult.Success(Unit), lease.publish())
            val source =
                CollaborationSpaceIncomingSource(
                    f.data.original.storeId,
                    f.data.original.matrixUserId,
                    f.data.original.matrixDeviceId,
                )
            assertEquals(source, f.repo.incomingSource(runtime))
            assertEquals(0, native.requests)
            val received =
                async(start = CoroutineStart.UNDISPATCHED) {
                    f.repo.observeBatches(runtime).first()
                }
            assertEquals(0, native.requests)
            native.responses.send(native.response())
            assertEquals(
                AppResult.Success(Unit),
                withTimeout(5_000) { f.repo.synchronize(runtime) },
            )
            val batch =
                assertIs<AppResult.Success<CollaborationSpaceSyncBatch>>(
                        withTimeout(5_000) { received.await() }
                    )
                    .value
            assertEquals(runtime, batch.runtime)
            assertEquals(source, batch.source)
            assertEquals("retained-response", batch.nextPosition)
            assertEquals("\$event", batch.rooms.single().headEventId)
            assertTrue(native.requests in 1..2)
            assertEquals(1, f.acquisitions)
            for (foreign in
                listOf(
                    runtime.copy(deviceId = SETUP_JOB),
                    runtime.copy(operationId = SETUP_DEVICE),
                    runtime.copy(owner = runtime.owner.copy(spaceId = SETUP_JOB)),
                )) {
                assertNull(f.repo.incomingSource(foreign))
                assertIs<AppResult.Failed>(f.repo.observeBatches(foreign).single())
            }
            assertEquals(1, f.acquisitions)
        } finally {
            native.responses.close()
            f.repo.closeSpace(f.owner)
            f.close()
        }
        assertEquals(1, native.closes)
    }

    @Test
    fun retiringBeforeFirstSyncEndsTheObserverWithoutAcquisition() = runBlocking {
        lateinit var native: SpaceRuntimeSyncClientSource
        val f =
            SpaceAdoptionStorageFixture.create { identity ->
                SpaceRuntimeSyncClientSource(identity).also { native = it }
            }
        try {
            f.select()
            val lease =
                assertIs<AppResult.Success<CollaborationSpaceRuntimeLease>>(f.install()).value
            val runtime =
                CollaborationSpaceRuntimeReady(
                    lease.owner,
                    lease.deviceId,
                    lease.installedOperationId,
                )
            assertNull(f.repo.incomingSource(runtime))
            assertEquals(AppResult.Success(Unit), lease.publish())
            val received =
                async(start = CoroutineStart.UNDISPATCHED) {
                    f.repo.observeBatches(runtime).single()
                }
            assertEquals(AppResult.Success(Unit), withTimeout(5_000) { f.repo.closeSpace(f.owner) })
            assertIs<AppResult.Failed>(withTimeout(5_000) { received.await() })
            assertNull(f.repo.incomingSource(runtime))
            assertEquals(0, native.requests)
        } finally {
            native.responses.close()
            f.repo.closeSpace(f.owner)
            f.close()
        }
        assertEquals(1, native.closes)
    }

    @Test
    fun retirementWhileNativeResponseIsPendingCannotPublishItToTheOldSpace() = runBlocking {
        lateinit var native: SpaceRuntimeSyncClientSource
        val f =
            SpaceAdoptionStorageFixture.create { identity ->
                SpaceRuntimeSyncClientSource(identity).also { native = it }
            }
        try {
            f.select()
            val lease =
                assertIs<AppResult.Success<CollaborationSpaceRuntimeLease>>(f.install()).value
            val runtime =
                CollaborationSpaceRuntimeReady(
                    lease.owner,
                    lease.deviceId,
                    lease.installedOperationId,
                )
            assertNull(f.repo.incomingSource(runtime))
            assertEquals(AppResult.Success(Unit), lease.publish())
            val received =
                async(start = CoroutineStart.UNDISPATCHED) {
                    f.repo.observeBatches(runtime).toList()
                }
            val syncing = async { f.repo.synchronize(runtime) }
            withTimeout(5_000) { native.entered.await() }
            val closing = async { f.repo.closeSpace(f.owner) }
            val terminal = withTimeout(5_000) { received.await() }
            assertEquals(1, terminal.size)
            assertIs<AppResult.Failed>(terminal.single())
            native.responses.send(native.response())
            assertIs<AppResult.Failed>(withTimeout(5_000) { syncing.await() })
            assertEquals(AppResult.Success(Unit), withTimeout(5_000) { closing.await() })
            assertNull(f.repo.incomingSource(runtime))
            assertEquals(1, native.requests)
        } finally {
            native.responses.close()
            f.repo.closeSpace(f.owner)
            f.close()
        }
        assertEquals(1, native.closes)
    }

    @Test
    fun neitherQueueCapabilityCreatesAnAbsentInstalledRuntime() = runTest {
        val f = SpaceAdoptionStorageFixture.create()
        try {
            f.select()
            val runtime = CollaborationSpaceRuntimeReady(f.owner, SETUP_DEVICE, SETUP_JOB)
            val originals = f.repo.acquireOriginalQueue(runtime, "!room:test")
            val staging = f.repo.acquireSpaceMessageQueue(runtime, "!room:test")
            assertEquals(assertIs<AppResult.Failed>(originals), assertIs<AppResult.Failed>(staging))
            assertEquals(originals, f.repo.findHead(runtime, "!room:test"))
            assertEquals(originals, f.repo.read(runtime, "!room:test", "\$event"))
            assertEquals(originals, f.repo.readPage(runtime, "!room:test", null))
            assertEquals(originals, f.repo.readPage(runtime, "!room:test", "next", "until"))
            assertNull(f.repo.incomingSource(runtime))
            assertEquals(originals, f.repo.observeBatches(runtime).single())
            assertIs<AppResult.Failed>(
                f.repo.read(setupOwner, SETUP_DEVICE, "!room:test", "\$event")
            )
            assertEquals(0, f.acquisitions)
            assertEquals(0, f.writes)
            assertEquals(0, f.privateReads)
        } finally {
            f.close()
        }
    }

    @Test
    fun unconfirmedRetiredOrForeignSetupCannotAcquireAnOriginalDirectory() = runTest {
        val f = SpaceAdoptionStorageFixture.create()
        try {
            f.select()
            for (setup in
                listOf(
                    f.setup.copy(outcome = CollaborationSpaceSetupOutcome.Pending),
                    f.setup.copy(successorId = SETUP_JOB),
                    f.setup.copy(
                        command =
                            f.setup.command.copy(
                                request = f.data.bootstrap.copy(deviceId = SETUP_JOB)
                            )
                    ),
                )) assertIs<AppResult.Failed>(f.repo.installOriginal(f.owner, f.original, setup))
            assertIs<AppResult.Failed>(
                f.repo.installOriginal(
                    f.owner.copy(parent = f.parent.copy(nativeSessionId = SETUP_JOB)),
                    f.original,
                    f.setup,
                )
            )
            assertEquals(0, f.acquisitions)
            assertEquals(0, f.writes)
        } finally {
            f.close()
        }
    }

    @Test
    fun missingDirectoryOrCredentialNeverCreatesAReplacementAndReleasesCapacity() = runTest {
        val f = SpaceAdoptionStorageFixture.create()
        try {
            f.select()
            f.present = false
            repeat(6) { assertIs<AppResult.Failed>(f.install()) }
            assertEquals(0, f.credentialCloses)
            f.present = true
            f.saved = null
            repeat(6) { assertIs<AppResult.Failed>(f.install()) }
            assertEquals(6, f.credentialCloses)
            assertEquals(6, f.directoryCloses)
            assertEquals(0, f.writes)
            assertEquals(0, f.privateReads)
        } finally {
            f.close()
        }
    }

    @Test
    fun changedHandoverAndWrongPrivateIdentityDoNotWriteCredentials() = runTest {
        val f = SpaceAdoptionStorageFixture.create()
        try {
            f.select()
            val retained = f.saved
            val wrong = f.original.copy(intent = f.original.intent.copy(operationId = SETUP_JOB))
            assertIs<AppResult.Failed>(f.repo.installOriginal(f.owner, wrong, f.setup))
            assertEquals(0, f.privateReads)
            f.identity = f.identity.copy(ed25519 = Base64.encode(ByteArray(32) { 55 }))
            assertIs<AppResult.Failed>(f.install())
            assertEquals(1, f.privateReads)
            assertEquals(0, f.writes)
            assertEquals(retained, f.saved)
            assertEquals(2, f.directoryCloses)
        } finally {
            f.close()
        }
    }

    @Test
    fun parentReplacementDuringPrivateReadPreventsCredentialPublication() = runTest {
        val f = SpaceAdoptionStorageFixture.create()
        try {
            f.select()
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val retained = f.saved
            f.beforePrivateRead = {
                entered.complete(Unit)
                resume.await()
            }
            val pending = async { f.install() }
            entered.await()
            f.repo.updateRetention(CollaborationRuntimeRetention(null, null))
            resume.complete(Unit)
            assertFailsWith<CancellationException> { pending.await() }
            assertEquals(0, f.writes)
            assertEquals(retained, f.saved)
            assertFalse(f.leased)
            assertEquals(
                AppResult.Success(false),
                f.repo.isSpaceOpen(f.owner, f.original.intent.deviceId, f.setup.command.id),
            )
        } finally {
            f.close()
        }
    }

    @Test
    fun cancellationRemainsCancellationEvenWhenBothResourceClosersFail() = runTest {
        val f = SpaceAdoptionStorageFixture.create()
        try {
            f.select()
            val retained = f.saved
            f.beforePrivateRead = { throw CancellationException("fixture cancellation") }
            f.failClose = true
            val pending = async { f.install() }
            assertFailsWith<CancellationException> { pending.await() }
            assertEquals(1, f.credentialCloses)
            assertEquals(1, f.directoryCloses)
            assertEquals(0, f.writes)
            assertEquals(retained, f.saved)
            assertFalse(f.leased)
        } finally {
            f.close()
        }
    }

    @Test
    fun failedProtectedWriteLeavesOriginalCredentialsAndNoRuntimeBehind() = runTest {
        val f = SpaceAdoptionStorageFixture.create()
        try {
            f.select()
            val retained = f.saved
            f.beforeWrite = { error("fixture storage failure") }
            repeat(2) { assertIs<AppResult.Failed>(f.install()) }
            assertEquals(2, f.writes)
            assertEquals(retained, f.saved)
            assertEquals(2, f.directoryCloses)
            assertEquals(
                AppResult.Success(false),
                f.repo.isSpaceOpen(f.owner, f.original.intent.deviceId, f.setup.command.id),
            )
        } finally {
            f.close()
        }
    }

    @Test
    fun groupPusherCannotRestoreAnAbsentRuntimeOrAcquireOriginalKeys() = runTest {
        val f = SpaceAdoptionStorageFixture.create()
        try {
            f.select()
            val before = f.saved
            val runtime =
                CollaborationSpaceRuntimeReady(
                    f.owner,
                    f.original.intent.deviceId,
                    f.setup.command.id,
                )
            repeat(3) { assertIs<AppResult.Failed>(f.repo.activate(runtime)) }
            assertEquals(0, f.acquisitions)
            assertEquals(0, f.writes)
            assertEquals(0, f.privateReads)
            assertEquals(before, f.saved)
        } finally {
            f.close()
        }
    }

    @Test
    fun groupSyncCannotRestoreAnAbsentRuntimeOrWriteOriginalCredentials() = runTest {
        val f = SpaceAdoptionStorageFixture.create()
        try {
            f.select()
            val before = f.saved
            val runtime =
                CollaborationSpaceRuntimeReady(
                    f.owner,
                    f.original.intent.deviceId,
                    f.setup.command.id,
                )
            repeat(3) { assertIs<AppResult.Failed>(f.repo.synchronize(runtime)) }
            assertEquals(0, f.acquisitions)
            assertEquals(0, f.writes)
            assertEquals(0, f.privateReads)
            assertEquals(before, f.saved)
        } finally {
            f.close()
        }
    }

    @Test
    fun groupHistoryCannotRestoreAnAbsentRuntimeOrWriteOriginalCredentials() = runTest {
        val f = SpaceAdoptionStorageFixture.create()
        try {
            f.select()
            val before = f.saved
            val runtime =
                CollaborationSpaceRuntimeReady(
                    f.owner,
                    f.original.intent.deviceId,
                    f.setup.command.id,
                )
            repeat(3) { assertIs<AppResult.Failed>(f.repo.read(runtime, "!original:example.test")) }
            assertEquals(0, f.acquisitions)
            assertEquals(0, f.writes)
            assertEquals(0, f.privateReads)
            assertEquals(before, f.saved)
        } finally {
            f.close()
        }
    }
}

private class SpaceAdoptionStorageFixture(
    val data: SdkAdoptionFixture,
    rawIdentity: CollaborationSdkIdentityData,
    expectedIdentity: CollaborationLocalDeviceIdentity,
    clients: AndroidCollaborationClientSource,
) {
    val parent =
        CollaborationNativeParent(
            setupOwner.sessionId,
            setupOwner.audience,
            setupOwner.accountId,
            setupOwner.nativeSessionId,
        )
    val owner = CollaborationSpaceOwner(parent, data.target.spaceId)
    var identity = rawIdentity
    val original =
        CollaborationExistingRuntimeHandover(
            CollaborationSpaceLegacySdk(setupOwner.setupBinding(), SETUP_DEVICE, expectedIdentity),
            data.target.copy(fingerprint = expectedIdentity.fingerprint),
        )
    val setup =
        CollaborationSpaceSetupRecord(
            data.command,
            2,
            1,
            CollaborationSpaceSetupOutcome.Bootstrapped(data.gateway),
        )
    var saved: String? =
        data.codec.encode(data.codec.stageExistingHandover(data.original, original.intent))
    var leased = false
    var present = true
    var acquisitions = 0
    var writes = 0
    var privateReads = 0
    var credentialCloses = 0
    var directoryCloses = 0
    var failClose = false
    var beforePrivateRead: suspend () -> Unit = {}
    var beforeWrite: () -> Unit = {}
    private val http = HttpClient(MockEngine { error("No network in pre-client adoption checks") })
    val repo =
        AndroidCollaborationRuntimeRepository(
            stores =
                object : CollaborationSdkStoreSource {
                    override suspend fun acquire(storeId: String): CollaborationSdkStoreLease =
                        error("Adoption must not create files")

                    override suspend fun acquireExisting(
                        storeId: String
                    ): CollaborationSdkStoreLease? {
                        acquisitions++
                        assertFalse(leased)
                        if (!present) return null
                        leased = true
                        return object : CollaborationSdkStoreLease {
                            override val storeId = storeId
                            override val databasePath = "/fixture/database"
                            override val cachePath = "/fixture/cache"
                            override val initiallyOccupied = true

                            override fun close() {
                                assertTrue(leased)
                                leased = false
                                directoryCloses++
                                if (failClose) error("fixture directory close")
                            }
                        }
                    }
                },
            credentialFactory = {
                assertTrue(leased)
                object : AtomicCredentialStore {
                    override val persistent = true

                    override suspend fun read() = saved

                    override suspend fun write(value: String?) {
                        assertTrue(leased)
                        writes++
                        beforeWrite()
                        saved = value
                    }

                    override suspend fun writeIfAbsent(value: String): String =
                        error("Adoption must not create credentials")

                    override fun close() {
                        credentialCloses++
                        if (failClose) error("fixture credential close")
                    }
                }
            },
            clients = clients,
            cipher = AesGcmContentCipher(),
            server = IdentityServer(SETUP_BASE, "Fixture"),
            endpoint = CollaborationSdkEndpoint("https://hris.example.test/"),
            http = http,
            privateIdentities =
                CollaborationSdkPrivateIdentitySource { path, passphrase ->
                    assertTrue(leased)
                    assertEquals("/fixture/database", path)
                    assertEquals(data.original.passphrase, passphrase)
                    privateReads++
                    beforePrivateRead()
                    identity
                },
        )

    suspend fun select() {
        assertEquals(
            AppResult.Success(Unit),
            repo.updateRetention(CollaborationRuntimeRetention(parent, setupOwner)),
        )
    }

    suspend fun install() = repo.installOriginal(owner, original, setup)

    fun close() {
        assertFalse(leased)
        http.close()
    }

    companion object {
        suspend fun create(
            clients: (CollaborationSdkIdentityData) -> AndroidCollaborationClientSource = {
                AndroidCollaborationSdkClientSource()
            }
        ): SpaceAdoptionStorageFixture {
            val data = SdkAdoptionFixture.create()
            val identity =
                CollaborationSdkIdentityData(
                    data.original.matrixUserId,
                    data.original.matrixDeviceId,
                    Base64.encode(ByteArray(32) { it.toByte() }),
                    Base64.encode(ByteArray(32) { (it + 32).toByte() }),
                )
            return SpaceAdoptionStorageFixture(
                data,
                identity,
                identity.toLocalDeviceIdentity(),
                clients(identity),
            )
        }
    }
}
