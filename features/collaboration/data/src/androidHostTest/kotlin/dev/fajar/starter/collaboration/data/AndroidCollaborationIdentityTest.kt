package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.dto.CollaborationGatewayDto
import dev.fajar.starter.collaboration.data.mappers.toGateway
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeRepository
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRuntimeInspection
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.*
import dev.fajar.starter.securestorage.*
import io.ktor.client.engine.mock.MockEngine
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class AndroidCollaborationIdentityTest {
    @Test
    fun anInspectionReservationBlocksBothRuntimePathsBeforeAnyFileAcquisition() = runTest {
        val f = Fixture()
        try {
            val parent =
                CollaborationNativeParent(
                    setupOwner.sessionId,
                    setupOwner.audience,
                    setupOwner.accountId,
                    setupOwner.nativeSessionId,
                )
            f.runtime.updateRetention(CollaborationRuntimeRetention(parent, setupOwner))
            val inspection =
                assertIs<CollaborationRuntimeInspection.Reserved>(
                    assertIs<AppResult.Success<CollaborationRuntimeInspection>>(
                            f.runtime.reserveInspection(setupOwner, SETUP_DEVICE)
                        )
                        .value
                )
            assertTrue(inspection.isCurrent())
            assertEquals(
                "client.collaboration_runtime_opening",
                assertIs<AppResult.Failed>(
                        f.runtime.acquireBackground(setupOwner, SETUP_DEVICE, 1_000)
                    )
                    .failure
                    .code,
            )
            assertEquals(
                "client.collaboration_runtime_opening",
                assertIs<AppResult.Failed>(f.runtime.restore(setupOwner, SETUP_DEVICE)).failure.code,
            )
            assertEquals(0, f.directories)
            assertEquals(0, f.reads)
            f.runtime.updateRetention(CollaborationRuntimeRetention(null, null))
            assertFalse(inspection.isCurrent())
            inspection.close()
            inspection.close()
            f.runtime.updateRetention(CollaborationRuntimeRetention(parent, setupOwner))
            f.allowDirectory = true
            // Once exclusion is released, the original occupied store still rejects missing keys.
            assertEquals(
                FailureKind.Storage,
                assertIs<AppResult.Failed>(f.runtime.restore(setupOwner, SETUP_DEVICE)).failure.kind,
            )
            assertEquals(1, f.directories)
            assertEquals(1, f.directoryCloses)
            assertEquals(0, f.writes)
        } finally {
            f.close()
        }
    }

    @Test
    fun inspectionCannotBorrowAnUnpublishedRuntimeAndCancellationReleasesItsExclusion() = runTest {
        val f = Fixture()
        try {
            f.store()
            f.allowDirectory = true
            val entered = CompletableDeferred<Unit>()
            f.privateIdentityRead = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val restoring = async { f.runtime.restore(setupOwner, SETUP_DEVICE) }
            entered.await()
            assertEquals(
                "client.collaboration_runtime_opening",
                assertIs<AppResult.Failed>(f.runtime.reserveInspection(setupOwner, SETUP_DEVICE))
                    .failure
                    .code,
            )
            assertEquals(1, f.directories)
            restoring.cancelAndJoin()
            val inspection =
                assertIs<CollaborationRuntimeInspection.Reserved>(
                    assertIs<AppResult.Success<CollaborationRuntimeInspection>>(
                            f.runtime.reserveInspection(setupOwner, SETUP_DEVICE)
                        )
                        .value
                )
            assertTrue(inspection.isCurrent())
            inspection.close()
            assertEquals(1, f.directoryCloses)
            assertEquals(0, f.writes)
        } finally {
            f.close()
        }
    }

    @Test
    fun missingOpenClientDoesNotReadCredentialsOpenSdkOrInventAnEmptyStore() = runTest {
        val f = Fixture()
        try {
            f.store()
            val before = f.values.toMap()
            val result =
                assertIs<AppResult.Failed>(f.runtime.readLocalIdentity(setupOwner, SETUP_DEVICE))
            assertEquals("client.collaboration_runtime_not_open", result.failure.code)
            assertEquals(before, f.values)
            assertEquals(0, f.reads)
            assertEquals(0, f.writes)
            assertEquals(0, f.directories)
            val cancelled = async {
                currentCoroutineContext().cancel()
                f.runtime.readLocalIdentity(setupOwner, SETUP_DEVICE)
            }
            cancelled.join()
            assertTrue(cancelled.isCancelled)
        } finally {
            f.close()
        }
    }

    private val handover =
        CollaborationRuntimeHandoverIntent(
            "a0000000-0000-0000-0000-000000000001",
            "a0000000-0000-0000-0000-000000000002",
            "a0000000-0000-0000-0000-000000000003",
            0,
            "a".repeat(64),
        )

    @Test
    fun retainedHandoverReplaysAfterLocalRestartWithoutOpeningOrWritingTheSdk() = runTest {
        val f = Fixture()
        try {
            f.store(expired = true, handover = handover)
            val saved = f.values.toMap()
            val restored = setupOwner.copy(sessionId = "restored-local")
            assertEquals(
                AppResult.Success(handover),
                f.runtime.handoverIntent(restored, SETUP_DEVICE),
            )
            assertEquals(
                AppResult.Success(handover),
                f.runtime.stageHandover(restored, SETUP_DEVICE, handover),
            )
            val changed =
                assertIs<AppResult.Failed>(
                    f.runtime.stageHandover(
                        restored,
                        SETUP_DEVICE,
                        handover.copy(expectedDeviceVersion = 1),
                    )
                )
            assertEquals("client.collaboration_handover_changed", changed.failure.code)
            assertEquals(
                AppResult.Success(CollaborationSelfIdentity(f.gateway.matrixUserId)),
                f.runtime.selfIdentity(restored, SETUP_DEVICE),
            )
            assertEquals(saved, f.values)
            assertEquals(0, f.writes)
            assertEquals(0, f.directories)
            assertEquals(4, f.closes)
        } finally {
            f.close()
        }
    }

    @Test
    fun aDurableIntentRejectsForegroundAndBackgroundLegacyRestoreBeforeSdkConstruction() = runTest {
        val f = Fixture()
        try {
            f.store(handover = handover)
            f.allowDirectory = true
            val saved = f.values.toMap()
            assertIs<AppResult.Failed>(f.runtime.restore(setupOwner, SETUP_DEVICE))
            val parent =
                CollaborationNativeParent(
                    setupOwner.sessionId,
                    setupOwner.audience,
                    setupOwner.accountId,
                    setupOwner.nativeSessionId,
                )
            assertEquals(
                AppResult.Success(Unit),
                f.runtime.updateRetention(CollaborationRuntimeRetention(parent, setupOwner)),
            )
            assertIs<AppResult.Failed>(f.runtime.acquireBackground(setupOwner, SETUP_DEVICE, 5_000))
            assertEquals(2, f.directories)
            assertEquals(2, f.directoryCloses)
            assertEquals(2, f.closes)
            assertEquals(0, f.writes)
            assertEquals(saved, f.values)
        } finally {
            f.close()
        }
    }

    @Test
    fun missingRuntimeAndCancelledIntentReadsCannotFabricateAStagedHandover() = runTest {
        val f = Fixture()
        try {
            f.store()
            val saved = f.values.toMap()
            assertEquals(
                AppResult.Success(null),
                f.runtime.handoverIntent(setupOwner, SETUP_DEVICE),
            )
            val absent =
                assertIs<AppResult.Failed>(
                    f.runtime.stageHandover(setupOwner, SETUP_DEVICE, handover)
                )
            assertEquals("client.collaboration_handover_pending", absent.failure.code)
            val entered = CompletableDeferred<Unit>()
            f.beforeRead = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val waiting = async { f.runtime.stageHandover(setupOwner, SETUP_DEVICE, handover) }
            entered.await()
            waiting.cancelAndJoin()
            assertEquals(3, f.closes)
            assertEquals(0, f.directories)
            assertEquals(0, f.writes)
            assertEquals(saved, f.values)
        } finally {
            f.close()
        }
    }

    @Test
    fun absentOrUninitializedRecordDoesNotOpenAClientOrCreateAnything() = runTest {
        val f = Fixture()
        try {
            assertEquals(AppResult.Success(null), f.runtime.selfIdentity(setupOwner, SETUP_DEVICE))
            f.store(initialized = false)
            assertEquals(AppResult.Success(null), f.runtime.selfIdentity(setupOwner, SETUP_DEVICE))
            assertEquals(2, f.reads)
            assertEquals(2, f.closes)
            assertEquals(0, f.writes)
        } finally {
            f.close()
        }
    }

    @Test
    fun initializedProtectedIdentitySurvivesExpiryAndLocalSessionRenewal() = runTest {
        val f = Fixture()
        try {
            f.store(expired = true)
            val original = f.values.toMap()
            for (owner in listOf(setupOwner, setupOwner.copy(sessionId = "renewed-local"))) {
                assertEquals(
                    AppResult.Success(CollaborationSelfIdentity(f.gateway.matrixUserId)),
                    f.runtime.selfIdentity(owner, SETUP_DEVICE),
                )
            }
            assertEquals(original, f.values)
            assertEquals(2, f.closes)
            assertEquals(0, f.writes)
        } finally {
            f.close()
        }
    }

    @Test
    fun anotherNativeFamilyCannotReadOrReuseTheOldProtectedRecord() = runTest {
        val f = Fixture()
        try {
            f.store()
            val other = setupOwner.copy(nativeSessionId = "aaaaaaaa-0000-0000-0000-000000000001")
            assertEquals(AppResult.Success(null), f.runtime.selfIdentity(other, SETUP_DEVICE))
            val otherScope = collaborationSdkScope(other, SETUP_DEVICE, f.server, f.endpoint)
            f.values[otherScope.credentialRecordName] = f.values.values.single()
            assertIs<AppResult.Failed>(f.runtime.selfIdentity(other, SETUP_DEVICE))
            assertEquals(0, f.writes)
        } finally {
            f.close()
        }
    }

    @Test
    fun corruptedOrUnprotectedStorageCannotExposeIdentity() = runTest {
        val f = Fixture()
        try {
            f.store()
            val name = f.values.keys.single()
            f.values[name] = "invalid-record"
            assertIs<AppResult.Failed>(f.runtime.selfIdentity(setupOwner, SETUP_DEVICE))
            f.store()
            f.persistent = false
            assertIs<AppResult.Failed>(f.runtime.selfIdentity(setupOwner, SETUP_DEVICE))
            assertEquals(1, f.reads)
            assertEquals(2, f.closes)
        } finally {
            f.close()
        }
    }

    @Test
    fun cancelledProtectedReadClosesItsHandleWithoutMutatingCredentials() = runTest {
        val f = Fixture()
        try {
            f.store()
            val entered = CompletableDeferred<Unit>()
            f.beforeRead = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val job = async { f.runtime.selfIdentity(setupOwner, SETUP_DEVICE) }
            entered.await()
            job.cancelAndJoin()
            assertEquals(1, f.closes)
            assertEquals(0, f.writes)
            assertEquals(1, f.values.size)
        } finally {
            f.close()
        }
    }

    @Test
    fun initializedCredentialsCannotRecreateAMissingPrivateAccountInEitherColdEntry() = runTest {
        val f = Fixture()
        try {
            f.store()
            f.allowDirectory = true
            f.privateIdentityRead = { error("Existing private account is missing") }
            val original = f.values.toMap()
            val parent =
                CollaborationNativeParent(
                    setupOwner.sessionId,
                    setupOwner.audience,
                    setupOwner.accountId,
                    setupOwner.nativeSessionId,
                )
            assertEquals(
                AppResult.Success(Unit),
                f.runtime.updateRetention(CollaborationRuntimeRetention(parent, setupOwner)),
            )
            repeat(6) { attempt ->
                assertIs<AppResult.Failed>(
                    if (attempt % 2 == 0) f.runtime.restore(setupOwner, SETUP_DEVICE)
                    else f.runtime.acquireBackground(setupOwner, SETUP_DEVICE, 5_000)
                )
            }
            assertEquals(6, f.privateReads)
            assertEquals(6, f.directories)
            assertEquals(6, f.directoryCloses)
            assertEquals(6, f.closes)
            assertEquals(0, f.writes)
            assertEquals(original, f.values)
            assertEquals(AppResult.Success(false), f.runtime.isOpen(setupOwner, SETUP_DEVICE))
        } finally {
            f.close()
        }
    }

    @Test
    fun foreignOrIncompletePrivateIdentityCannotRestoreAnInitializedCredentialRecord() = runTest {
        val f = Fixture()
        try {
            f.store()
            f.allowDirectory = true
            val original = f.values.toMap()
            val identity =
                CollaborationSdkIdentityData(
                    f.gateway.matrixUserId,
                    f.gateway.matrixDeviceId,
                    Base64.encode(ByteArray(32) { 1 }).trimEnd('='),
                    Base64.encode(ByteArray(32) { 2 }).trimEnd('='),
                )
            for (invalid in
                listOf(
                    identity.copy(userId = "@other:matrix.example.test"),
                    identity.copy(deviceId = "OTHERDEVICE"),
                    identity.copy(ed25519 = null),
                    identity.copy(curve25519 = "invalid"),
                )) {
                f.privateIdentityRead = { invalid }
                assertIs<AppResult.Failed>(f.runtime.restore(setupOwner, SETUP_DEVICE))
            }
            assertEquals(4, f.privateReads)
            assertEquals(4, f.directoryCloses)
            assertEquals(4, f.closes)
            assertEquals(0, f.writes)
            assertEquals(original, f.values)
        } finally {
            f.close()
        }
    }

    @Test
    fun missingPrivateAccountRejectsCredentialReplacementBeforeTheProtectedWrite() = runTest {
        val f = Fixture()
        try {
            f.store()
            f.allowDirectory = true
            f.privateIdentityRead = { error("Existing private account is missing") }
            val original = f.values.toMap()
            assertIs<AppResult.Failed>(f.runtime.install(setupOwner, f.replacement))
            assertEquals(1, f.privateReads)
            assertEquals(1, f.directoryCloses)
            assertEquals(1, f.closes)
            assertEquals(0, f.writes)
            assertEquals(original, f.values)
        } finally {
            f.close()
        }
    }

    @Test
    fun interruptedCredentialReplacementStillRequiresTheOriginalPrivateAccount() = runTest {
        val f = Fixture()
        try {
            val record = f.store(interruptedReplacement = true)
            assertFalse(record.initialized)
            assertNotEquals(record.bootstrapOperationId, record.messageOperationId)
            f.allowDirectory = true
            f.privateIdentityRead = { error("Previous private account is missing") }
            val original = f.values.toMap()
            assertIs<AppResult.Failed>(f.runtime.restore(setupOwner, SETUP_DEVICE))
            assertEquals(1, f.privateReads)
            assertEquals(1, f.directoryCloses)
            assertEquals(1, f.closes)
            assertEquals(0, f.writes)
            assertEquals(original, f.values)
        } finally {
            f.close()
        }
    }

    @Test
    fun aReplacementGatewayCannotCreateKeysWhenTheOriginalRecordAndDirectoryAreMissing() = runTest {
        val f = Fixture()
        try {
            f.allowDirectory = true
            f.occupied = false
            assertIs<AppResult.Failed>(f.runtime.install(setupOwner, f.replacement))
            assertEquals(0, f.privateReads)
            assertEquals(0, f.writes)
            assertTrue(f.values.isEmpty())
            assertEquals(1, f.directoryCloses)
            assertEquals(1, f.closes)
        } finally {
            f.close()
        }
    }

    @Test
    fun firstBootstrapStillPreparesProtectedCredentialsBeforeAnyNativeConstruction() = runTest {
        val f = Fixture()
        try {
            f.allowDirectory = true
            f.occupied = false
            val initial =
                CollaborationSetupCommandRecord(
                    f.command,
                    2,
                    1,
                    CollaborationSetupOutcome.Bootstrapped(f.gateway),
                )
            // This fixture rejects the protected write; a first bootstrap must reach that
            // durable boundary, then stop without constructing a native client or losing handles.
            assertIs<AppResult.Failed>(f.runtime.install(setupOwner, initial))
            assertEquals(1, f.writes)
            assertEquals(0, f.privateReads)
            assertTrue(f.values.isEmpty())
            assertEquals(1, f.directoryCloses)
            assertEquals(1, f.closes)
        } finally {
            f.close()
        }
    }

    @Test
    fun cancellationDuringPrivateVerificationReleasesTheDirectoryAndPreservesCredentials() =
        runTest {
            val f = Fixture()
            try {
                f.store()
                f.allowDirectory = true
                val original = f.values.toMap()
                val entered = CompletableDeferred<Unit>()
                f.privateIdentityRead = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                val restoring = async { f.runtime.restore(setupOwner, SETUP_DEVICE) }
                entered.await()
                restoring.cancelAndJoin()
                assertTrue(restoring.isCancelled)
                assertEquals(1, f.privateReads)
                assertEquals(1, f.directoryCloses)
                assertEquals(1, f.closes)
                assertEquals(0, f.writes)
                assertEquals(original, f.values)

                f.privateIdentityRead = { error("Private account remains unavailable") }
                assertIs<AppResult.Failed>(f.runtime.restore(setupOwner, SETUP_DEVICE))
                assertEquals(2, f.directoryCloses)
                assertEquals(2, f.closes)
            } finally {
                f.close()
            }
        }

    private class Fixture {
        val server = IdentityServer(SETUP_BASE, "Fictional phone")
        val endpoint = CollaborationSdkEndpoint("https://hris.example.test/")
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
                .toGateway(
                    setupOwner,
                    CollaborationBootstrap(SETUP_OPERATION, SETUP_DEVICE, 1, null),
                )
        val replacementGateway =
            CollaborationGateway(
                gateway.owner,
                gateway.deviceId,
                gateway.sessionId,
                gateway.version + 1,
                gateway.matrixUserId,
                gateway.matrixDeviceId,
                gateway.accessToken,
                gateway.refreshToken,
                gateway.accessExpiresAtEpochMillis,
                gateway.expiresAtEpochMillis,
            )
        val replacement =
            CollaborationSetupCommandRecord(
                command.copy(
                    id = "a0000000-0000-0000-0000-000000000099",
                    action =
                        (command.action as CollaborationSetupAction.Bootstrap).copy(
                            gatewayVersion = gateway.version
                        ),
                ),
                2,
                1,
                CollaborationSetupOutcome.Bootstrapped(replacementGateway),
            )
        val values = mutableMapOf<String, String>()
        var persistent = true
        var reads = 0
        var writes = 0
        var closes = 0
        var allowDirectory = false
        var occupied = true
        var directories = 0
        var directoryCloses = 0
        var beforeRead: suspend () -> Unit = {}
        var privateReads = 0
        var privateIdentityRead: suspend () -> CollaborationSdkIdentityData = {
            error("No private identity read expected")
        }
        private val http =
            createHttpClient(MockEngine { error("No identity network request") }, endpoint.baseUrl)
        val runtime =
            AndroidCollaborationRuntimeRepository(
                object : CollaborationSdkStoreSource {
                    override suspend fun acquireExisting(
                        storeId: String
                    ): CollaborationSdkStoreLease? =
                        error("Existing SDK acquisition is not part of this fixture.")

                    override suspend fun acquire(storeId: String): CollaborationSdkStoreLease {
                        check(allowDirectory) { "No identity directory acquisition" }
                        directories++
                        return object : CollaborationSdkStoreLease {
                            override val storeId = storeId
                            override val initiallyOccupied = occupied
                            override val databasePath = "must-not-open-sdk/database"
                            override val cachePath = "must-not-open-sdk/cache"

                            override fun close() {
                                directoryCloses++
                            }
                        }
                    }
                },
                { name ->
                    object : AtomicCredentialStore {
                        override val persistent
                            get() = this@Fixture.persistent

                        override suspend fun read(): String? {
                            reads++
                            beforeRead()
                            return values[name]
                        }

                        override suspend fun write(value: String?) {
                            writes++
                            error("No identity credential write")
                        }

                        override suspend fun writeIfAbsent(value: String): String {
                            writes++
                            error("No identity key creation")
                        }

                        override fun close() {
                            closes++
                        }
                    }
                },
                AndroidCollaborationSdkClientSource(),
                AesGcmContentCipher(),
                server,
                endpoint,
                http,
                privateIdentities =
                    CollaborationSdkPrivateIdentitySource { _, _ ->
                        privateReads++
                        privateIdentityRead()
                    },
            )

        suspend fun store(
            initialized: Boolean = true,
            expired: Boolean = false,
            handover: CollaborationRuntimeHandoverIntent? = null,
            interruptedReplacement: Boolean = false,
        ): CollaborationSdkRecord {
            val scope = collaborationSdkScope(setupOwner, SETUP_DEVICE, server, endpoint)
            val codec = CollaborationSdkRecordCodec(scope)
            var record =
                codec
                    .create(command, gateway, Base64.encode(ByteArray(32) { 7 }))
                    .copy(initialized = initialized)
            if (expired)
                record =
                    record.copy(
                        initialAccessExpiresAt = "2026-01-01T00:00:00Z",
                        expiresAt = "2026-01-02T00:00:00Z",
                    )
            if (handover != null) record = codec.stageHandover(record, handover)
            if (interruptedReplacement)
                record = codec.replaceCredentials(record, replacement.command, replacementGateway)
            values[scope.credentialRecordName] = codec.encode(record)
            return record
        }

        fun close() {
            http.cancelAndClose()
        }
    }
}
