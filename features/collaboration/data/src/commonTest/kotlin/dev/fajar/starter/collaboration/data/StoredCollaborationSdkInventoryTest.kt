package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.dto.CollaborationGatewayDto
import dev.fajar.starter.collaboration.data.mappers.toGateway
import dev.fajar.starter.collaboration.data.repositories.StoredCollaborationSdkInventoryRepository
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

class StoredCollaborationSdkInventoryTest {
    @Test
    fun originalRecordIsDecodedAfterLoginReplacementWithoutExposingOrWritingSecrets() = runTest {
        val f = Fixture.create()
        val expected = f.codec.initialized(f.original)
        f.saved = f.codec.encode(expected)
        val before = f.saved
        val result =
            assertIs<AppResult.Success<CollaborationSdkInventory>>(
                    f.repository().inspect(setupOwner.copy(sessionId = "new-local"), SETUP_DEVICE)
                )
                .value
        assertEquals(setupOwner.setupBinding(), result.binding)
        assertEquals(SETUP_DEVICE, result.deviceId)
        assertTrue(
            result.directoryPresent && result.cryptoDatabasePresent && result.stateDatabasePresent
        )
        val credential = assertNotNull(result.credential)
        assertEquals(expected.messageOperationId, credential.messageOperationId)
        assertEquals(expected.bootstrapOperationId, credential.bootstrapOperationId)
        assertEquals(expected.credentialRevision, credential.revision)
        assertTrue(credential.initialized)
        assertNull(credential.handover)
        for (secret in
            listOf(
                expected.accessToken,
                expected.refreshToken,
                expected.passphrase,
                expected.storeId,
                expected.matrixUserId,
            )) {
            assertFalse(result.toString().contains(secret))
            assertFalse(credential.toString().contains(secret))
        }
        assertEquals(before, f.saved)
        assertEquals(
            listOf("acquire", "factory", "read", "credential-close", "directory-close"),
            f.events,
        )
    }

    @Test
    fun rotatedAndFrozenRecordsKeepTheirOriginalQueueAndCompleteHandover() = runTest {
        val f = Fixture.create()
        val initialized = f.codec.initialized(f.original)
        val rotated =
            f.codec.replaceCredentials(
                initialized,
                f.command.copy(
                    id = SETUP_JOB,
                    action = CollaborationSetupAction.Bootstrap(SETUP_DEVICE, 1, f.gateway.version),
                ),
                CollaborationGateway(
                    f.gateway.owner,
                    f.gateway.deviceId,
                    f.gateway.sessionId,
                    f.gateway.version + 1,
                    f.gateway.matrixUserId,
                    f.gateway.matrixDeviceId,
                    f.gateway.accessToken,
                    f.gateway.refreshToken,
                    f.gateway.accessExpiresAtEpochMillis,
                    f.gateway.expiresAtEpochMillis,
                ),
            )
        val handover =
            CollaborationRuntimeHandoverIntent(
                SETUP_JOB,
                SETUP_OPERATION,
                SETUP_DEVICE,
                0,
                "a".repeat(64),
            )
        val frozen = f.codec.stageHandover(initialized, handover)
        for (record in listOf(rotated, frozen)) {
            f.saved = f.codec.encode(record)
            val summary =
                assertNotNull(
                    assertIs<AppResult.Success<CollaborationSdkInventory>>(
                            f.repository().inspect(setupOwner, SETUP_DEVICE)
                        )
                        .value
                        .credential
                )
            assertEquals(SETUP_OPERATION, summary.messageOperationId)
            assertEquals(record.bootstrapOperationId, summary.bootstrapOperationId)
            assertEquals(record.initialized, summary.initialized)
            assertEquals(f.codec.handoverIntent(record), summary.handover)
            assertEquals(record, f.codec.decode(assertNotNull(f.saved)))
        }
    }

    @Test
    fun absentDirectoryAndAbsentCredentialsAreIndependentFacts() = runTest {
        val f = Fixture.create()
        f.directoryPresent = false
        val retained =
            assertIs<AppResult.Success<CollaborationSdkInventory>>(
                    f.repository().inspect(setupOwner, SETUP_DEVICE)
                )
                .value
        assertFalse(
            retained.directoryPresent ||
                retained.cryptoDatabasePresent ||
                retained.stateDatabasePresent
        )
        assertNotNull(retained.credential)
        f.saved = null
        f.directoryPresent = true
        val missing =
            assertIs<AppResult.Success<CollaborationSdkInventory>>(
                    f.repository().inspect(setupOwner, SETUP_DEVICE)
                )
                .value
        assertTrue(missing.directoryPresent)
        assertNull(missing.credential)
        assertNull(f.saved)
    }

    @Test
    fun malformedOrForeignCredentialsRemainStorageFailureAndReleaseBothOwners() = runTest {
        val f = Fixture.create()
        for (raw in
            listOf(
                "{}",
                "not json",
                Json.encodeToString(f.original.copy(accountId = SETUP_DEVICE)),
            )) {
            f.saved = raw
            f.events.clear()
            assertIs<AppResult.Failed>(f.repository().inspect(setupOwner, SETUP_DEVICE))
            assertEquals(raw, f.saved)
            assertEquals(listOf("credential-close", "directory-close"), f.events.takeLast(2))
        }
    }

    @Test
    fun nonpersistentStoreOrFactoryFailureCannotEscapeTheDirectoryLease() = runTest {
        val f = Fixture.create()
        f.persistent = false
        assertIs<AppResult.Failed>(f.repository().inspect(setupOwner, SETUP_DEVICE))
        assertFalse("read" in f.events)
        assertEquals(listOf("credential-close", "directory-close"), f.events.takeLast(2))
        f.events.clear()
        f.beforeFactory = { error("fixture factory unavailable") }
        assertIs<AppResult.Failed>(f.repository().inspect(setupOwner, SETUP_DEVICE))
        assertEquals(listOf("acquire", "factory", "directory-close"), f.events)
    }

    @Test
    fun busyOrMismatchedDirectoryNeverReadsTheCredential() = runTest {
        val f = Fixture.create()
        f.beforeAcquire = { throw CollaborationSdkStoreBusyException() }
        assertIs<AppResult.Failed>(f.repository().inspect(setupOwner, SETUP_DEVICE))
        assertEquals(listOf("acquire"), f.events)
        f.beforeAcquire = {}
        f.leaseId = "b".repeat(64)
        f.events.clear()
        assertIs<AppResult.Failed>(f.repository().inspect(setupOwner, SETUP_DEVICE))
        assertEquals(listOf("acquire", "directory-close"), f.events)
    }

    @Test
    fun cancellationWhileReadingPreservesItsCauseAndClosesBothResourcesDespiteCloseFailure() =
        runTest {
            val f = Fixture.create()
            val cancelled = CancellationException("fixture cancellation")
            val closeFailure = IllegalStateException("fixture close")
            f.beforeRead = { throw cancelled }
            f.beforeCredentialClose = { throw closeFailure }
            val actual =
                assertFailsWith<CancellationException> {
                    f.repository().inspect(setupOwner, SETUP_DEVICE)
                }
            assertSame(cancelled, actual)
            assertSame(closeFailure, actual.suppressedExceptions.single())
            assertEquals(listOf("credential-close", "directory-close"), f.events.takeLast(2))
        }

    @Test
    fun coroutineDisposalClosesTheHeldDirectoryAndDoesNotPublishAResult() = runTest {
        val f = Fixture.create()
        val entered = CompletableDeferred<Unit>()
        f.beforeRead = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pending = async { f.repository().inspect(setupOwner, SETUP_DEVICE) }
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
                        override val cryptoDatabasePresent = true
                        override val stateDatabasePresent = true

                        override fun close() {
                            events += "directory-close"
                        }
                    }
                }
            }

        fun repository() =
            StoredCollaborationSdkInventoryRepository(
                source,
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
