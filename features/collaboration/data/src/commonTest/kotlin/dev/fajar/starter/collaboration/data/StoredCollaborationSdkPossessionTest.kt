package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.dto.CollaborationGatewayDto
import dev.fajar.starter.collaboration.data.mappers.toGateway
import dev.fajar.starter.collaboration.data.repositories.StoredCollaborationSdkPossessionRepository
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.AtomicCredentialStore
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json

class StoredCollaborationSdkPossessionTest {
    @Test
    fun uninitializedHandoverKeepsPrivateIdentityAcrossRepositoryAndLoginReplacement() = runTest {
        val f = Fixture.create()
        val target =
            CollaborationRuntimeHandoverIntent(
                SETUP_JOB,
                SETUP_OPERATION,
                SETUP_DEVICE,
                0,
                "a".repeat(64),
            )
        val held = f.codec.stageExistingHandover(f.original, target)
        assertEquals(3, held.schema)
        f.saved = f.codec.encode(held)
        val before = f.saved
        var identity: CollaborationLocalDeviceIdentity? = null
        for (session in listOf("new-login", "later-login")) {
            f.events.clear()
            val observed =
                assertNotNull(
                    assertIs<AppResult.Success<CollaborationSdkPossession?>>(
                            f.repository()
                                .readPrivateIdentity(
                                    setupOwner.copy(sessionId = session),
                                    SETUP_DEVICE,
                                )
                        )
                        .value
                )
            val credential = assertNotNull(observed.inventory.credential)
            assertFalse(credential.initialized)
            assertEquals(target, credential.handover)
            assertEquals(f.original.messageOperationId, credential.messageOperationId)
            if (identity != null) assertEquals(identity, observed.identity)
            identity = observed.identity
            assertEquals(before, f.saved)
        }
    }

    @Test
    fun readsOriginalPrivateAccountUnderOneLeaseWithoutWritingCredentials() = runTest {
        val f = Fixture.create()
        val original = f.saved
        val result =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationSdkPossession?>>(
                        f.repository()
                            .readPrivateIdentity(
                                setupOwner.copy(sessionId = "new-login"),
                                SETUP_DEVICE,
                            )
                    )
                    .value
            )
        assertEquals(setupOwner.setupBinding(), result.inventory.binding)
        assertEquals(SETUP_DEVICE, result.inventory.deviceId)
        assertEquals(f.original.matrixUserId, result.identity.matrixUserId)
        assertEquals(f.original.matrixDeviceId, result.identity.matrixDeviceId)
        assertTrue(result.identity.fingerprint.matches(Regex("[a-f0-9]{64}")))
        assertEquals(
            f.original.messageOperationId,
            result.inventory.credential!!.messageOperationId,
        )
        assertEquals(original, f.saved)
        assertEquals(
            listOf("acquire", "factory", "read", "native", "credential-close", "directory-close"),
            f.events,
        )
        for (secret in
            listOf(
                f.original.passphrase,
                f.original.accessToken,
                f.original.refreshToken,
                f.scope.storeId,
                f.original.matrixUserId,
            )) {
            assertFalse(result.toString().contains(secret))
        }
    }

    @Test
    fun rotatedOrFrozenCredentialProvenanceDoesNotReplaceTheOriginalQueueOrKeys() = runTest {
        val f = Fixture.create()
        val initialized = f.codec.initialized(f.original)
        val frozen =
            f.codec.stageHandover(
                initialized,
                CollaborationRuntimeHandoverIntent(
                    SETUP_JOB,
                    SETUP_OPERATION,
                    SETUP_DEVICE,
                    0,
                    "a".repeat(64),
                ),
            )
        for (record in
            listOf(f.original.copy(initialized = false, credentialRevision = 3), frozen)) {
            f.saved = f.codec.encode(record)
            f.statePresent = false
            f.events.clear()
            val result =
                assertNotNull(
                    assertIs<AppResult.Success<CollaborationSdkPossession?>>(
                            f.repository().readPrivateIdentity(setupOwner, SETUP_DEVICE)
                        )
                        .value
                )
            assertFalse(result.inventory.stateDatabasePresent)
            assertEquals(record.initialized, result.inventory.credential!!.initialized)
            assertEquals(
                record.messageOperationId,
                result.inventory.credential!!.messageOperationId,
            )
            assertEquals(f.codec.handoverIntent(record), result.inventory.credential!!.handover)
            assertEquals(record, f.codec.decode(assertNotNull(f.saved)))
        }
    }

    @Test
    fun absentMaterialDoesNotCallNativeOrCreateAnything() = runTest {
        for (stage in 0..2) {
            val f = Fixture.create()
            when (stage) {
                0 -> f.directoryPresent = false
                1 -> f.cryptoPresent = false
                2 -> f.saved = null
            }
            assertNull(
                assertIs<AppResult.Success<CollaborationSdkPossession?>>(
                        f.repository().readPrivateIdentity(setupOwner, SETUP_DEVICE)
                    )
                    .value
            )
            assertFalse("native" in f.events)
            assertEquals(
                if (stage == 0) listOf("acquire")
                else if (stage == 1) listOf("acquire", "directory-close")
                else listOf("acquire", "factory", "read", "credential-close", "directory-close"),
                f.events,
            )
        }
    }

    @Test
    fun malformedOrForeignCredentialCannotReachTheNativeReader() = runTest {
        for (raw in listOf("{}", "not json")) {
            val f = Fixture.create()
            f.saved = raw
            assertEquals(
                FailureKind.Storage,
                assertIs<AppResult.Failed>(
                        f.repository().readPrivateIdentity(setupOwner, SETUP_DEVICE)
                    )
                    .failure
                    .kind,
            )
            assertFalse("native" in f.events)
            assertEquals(raw, f.saved)
            assertEquals(listOf("credential-close", "directory-close"), f.events.takeLast(2))
        }
        val f = Fixture.create()
        f.saved = Json.encodeToString(f.original.copy(accountId = SETUP_DEVICE))
        assertIs<AppResult.Failed>(f.repository().readPrivateIdentity(setupOwner, SETUP_DEVICE))
        assertFalse("native" in f.events)
    }

    @Test
    fun acquisitionFactoryAndNativeFailuresCloseOnlyAcquiredResources() = runTest {
        for (stage in 0..3) {
            val f = Fixture.create()
            when (stage) {
                0 -> f.beforeAcquire = { throw CollaborationSdkStoreBusyException() }
                1 -> f.beforeFactory = { error("fixture credential acquisition failed") }
                2 -> f.persistent = false
                3 -> f.beforeIdentity = { error("fixture private account corrupt") }
            }
            assertIs<AppResult.Failed>(f.repository().readPrivateIdentity(setupOwner, SETUP_DEVICE))
            when (stage) {
                0 -> assertEquals(listOf("acquire"), f.events)
                1 -> assertEquals(listOf("acquire", "factory", "directory-close"), f.events)
                else ->
                    assertEquals(
                        listOf("credential-close", "directory-close"),
                        f.events.takeLast(2),
                    )
            }
        }
    }

    @Test
    fun cancellationClosesBothOwnersAndPreservesItsCauseAcrossCloseFailure() = runTest {
        val f = Fixture.create()
        val cancelled = CancellationException("fixture cancellation")
        val closing = IllegalStateException("fixture closing failure")
        f.beforeIdentity = { throw cancelled }
        f.beforeCredentialClose = { throw closing }
        val actual =
            assertFailsWith<CancellationException> {
                f.repository().readPrivateIdentity(setupOwner, SETUP_DEVICE)
            }
        assertSame(cancelled, actual)
        assertSame(closing, actual.suppressedExceptions.single())
        assertEquals(listOf("credential-close", "directory-close"), f.events.takeLast(2))
    }

    @Test
    fun pendingReadRetainsOwnersUntilDatasourceCancellationCompletes() = runTest {
        val f = Fixture.create()
        val entered = CompletableDeferred<Unit>()
        f.beforeIdentity = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pending = async { f.repository().readPrivateIdentity(setupOwner, SETUP_DEVICE) }
        entered.await()
        assertFalse("directory-close" in f.events)
        pending.cancel()
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(listOf("credential-close", "directory-close"), f.events.takeLast(2))
    }

    private class Fixture(val scope: CollaborationSdkScope) {
        companion object {
            suspend fun create() =
                Fixture(
                    collaborationSdkScope(
                        setupOwner,
                        SETUP_DEVICE,
                        IdentityServer(SETUP_BASE, "Fixture phone"),
                        CollaborationSdkEndpoint("https://hris.example.test/"),
                    )
                )
        }

        val endpoint = CollaborationSdkEndpoint("https://hris.example.test/")
        val server = IdentityServer(SETUP_BASE, "Fixture phone")
        val codec = CollaborationSdkRecordCodec(scope)
        val command =
            CollaborationSetupCommand(
                SETUP_OPERATION,
                setupOwner.setupBinding(),
                CollaborationNativePlatform.ANDROID,
                5,
                Instant.parse("2026-10-31T00:00:00Z"),
                CollaborationSetupAction.Bootstrap(SETUP_DEVICE, 1, null),
            )
        val gateway =
            Json.decodeFromString<CollaborationGatewayDto>(setupGatewayJson)
                .toGateway(setupOwner, setupBootstrap)
        val original = codec.create(command, gateway, Base64.encode(ByteArray(32) { it.toByte() }))
        var saved: String? = codec.encode(original)
        val events = mutableListOf<String>()
        var directoryPresent = true
        var persistent = true
        var cryptoPresent = true
        var statePresent = true
        var beforeIdentity: suspend () -> Unit = {}
        val nativeIdentity =
            CollaborationSdkIdentityData(
                original.matrixUserId,
                original.matrixDeviceId,
                Base64.encode(ByteArray(32) { it.toByte() }),
                Base64.encode(ByteArray(32) { (it + 32).toByte() }),
            )
        var leaseId = scope.storeId
        var beforeFactory: () -> Unit = {}
        var beforeAcquire: suspend () -> Unit = {}
        var beforeRead: suspend () -> Unit = {}
        var beforeCredentialClose: () -> Unit = {}
        private val source =
            object : CollaborationSdkInventorySource {
                override suspend fun inspect(storeId: String): CollaborationSdkInventoryLease? {
                    events += "acquire"
                    assertEquals(scope.storeId, storeId)
                    beforeAcquire()
                    if (!directoryPresent) return null
                    return object : CollaborationSdkInventoryLease {
                        override val storeId = leaseId
                        override val databasePath = "/fixture/database"
                        override val cryptoDatabasePresent = cryptoPresent
                        override val stateDatabasePresent = statePresent

                        override fun close() {
                            events += "directory-close"
                        }
                    }
                }
            }

        fun repository() =
            StoredCollaborationSdkPossessionRepository(
                source,
                CollaborationSdkPrivateIdentitySource { path, passphrase ->
                    events += "native"
                    assertEquals("/fixture/database", path)
                    assertEquals(original.passphrase, passphrase)
                    assertFalse("directory-close" in events)
                    beforeIdentity()
                    nativeIdentity
                },
                { name ->
                    events += "factory"
                    assertEquals(scope.credentialRecordName, name)
                    beforeFactory()
                    object : AtomicCredentialStore {
                        override val persistent
                            get() = this@Fixture.persistent

                        override suspend fun read(): String? {
                            events += "read"
                            beforeRead()
                            return saved
                        }

                        override suspend fun write(value: String?) {
                            error("Inventory cannot write credentials")
                        }

                        override suspend fun writeIfAbsent(value: String): String =
                            error("Inventory cannot enroll")

                        override fun close() {
                            events += "credential-close"
                            beforeCredentialClose()
                        }
                    }
                },
                server,
                endpoint,
            )
    }
}
