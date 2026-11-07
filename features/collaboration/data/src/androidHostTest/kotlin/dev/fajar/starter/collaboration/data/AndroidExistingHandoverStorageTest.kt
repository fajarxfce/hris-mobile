@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.dto.CollaborationGatewayDto
import dev.fajar.starter.collaboration.data.mappers.toGateway
import dev.fajar.starter.collaboration.data.mappers.toLocalDeviceIdentity
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeRepository
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json

class AndroidExistingHandoverStorageTest {
    @Test
    fun uninitializedPrivateStoreStagesAndRestartsWithoutClaimingInitialization() = runTest {
        val f = ExistingHandoverStorageFixture.create()
        try {
            val retained = f.original.copy(initialized = false)
            f.saved = f.codec.encode(retained)
            val repo = f.repository()
            f.select(repo)
            assertEquals(
                AppResult.Success(f.target.intent),
                repo.stageExistingHandover(f.current, f.target),
            )
            val staged = f.codec.decode(assertNotNull(f.saved))
            assertFalse(staged.initialized)
            assertEquals(3, staged.schema)
            assertEquals(
                retained,
                staged.copy(
                    schema = 1,
                    credentialRevision = retained.credentialRevision,
                    handover = null,
                ),
            )
            val reopened = f.repository()
            f.select(reopened)
            assertEquals(
                AppResult.Success(f.target.intent),
                reopened.stageExistingHandover(f.current, f.target),
            )
            assertEquals(staged, f.codec.decode(assertNotNull(f.saved)))
            assertEquals(1, f.writes)
            assertEquals(0, f.newStoreAcquisitions)
            assertEquals(0, f.httpRequests)
        } finally {
            f.close()
        }
    }

    @Test
    fun uninitializedRecordCannotSubstituteForMissingOrDifferentPrivateMaterial() = runTest {
        for (missing in 0..4) {
            val f = ExistingHandoverStorageFixture.create()
            try {
                f.saved = f.codec.encode(f.original.copy(initialized = false))
                when (missing) {
                    0 -> f.directoryPresent = false
                    1 -> f.cryptoPresent = false
                    2 -> f.statePresent = false
                    3 -> f.identity = f.identity.copy(ed25519 = Base64.encode(ByteArray(32) { 8 }))
                    4 -> f.beforePrivateRead = { error("private account unavailable") }
                }
                val before = f.saved
                val repo = f.repository()
                f.select(repo)
                assertIs<AppResult.Failed>(repo.stageExistingHandover(f.current, f.target))
                assertEquals(before, f.saved)
                assertEquals(0, f.writes)
                assertEquals(0, f.newStoreAcquisitions)
                assertEquals(0, f.httpRequests)
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun uninitializedCommittedIntentSurvivesCancellationWithoutRetargeting() = runTest {
        val f = ExistingHandoverStorageFixture.create()
        try {
            f.saved = f.codec.encode(f.original.copy(initialized = false))
            val repo = f.repository()
            f.select(repo)
            f.afterWrite = { throw CancellationException("interrupted after commit") }
            assertFailsWith<CancellationException> {
                repo.stageExistingHandover(f.current, f.target)
            }
            val committed = assertNotNull(f.saved)
            assertFalse(f.codec.decode(committed).initialized)
            assertEquals(f.target.intent, f.codec.handoverIntent(f.codec.decode(committed)))
            f.afterWrite = {}
            val reopened = f.repository()
            f.select(reopened)
            assertEquals(
                AppResult.Success(f.target.intent),
                reopened.stageExistingHandover(f.current, f.target),
            )
            assertIs<AppResult.Failed>(
                reopened.stageExistingHandover(
                    f.current,
                    f.target.copy(intent = f.target.intent.copy(expectedDeviceVersion = 2)),
                )
            )
            assertEquals(committed, f.saved)
            assertEquals(1, f.writes)
        } finally {
            f.close()
        }
    }

    @Test
    fun closedOriginalKeysAndQueueProvenanceSurviveExactStagingAndRepositoryRestart() = runTest {
        val f = ExistingHandoverStorageFixture.create()
        try {
            val repo = f.repository()
            f.select(repo)
            assertEquals(
                AppResult.Success(f.target.intent),
                repo.stageExistingHandover(f.current, f.target),
            )
            val stored = f.codec.decode(assertNotNull(f.saved))
            assertEquals(f.codec.stageHandover(f.original, f.target.intent), stored)
            assertEquals(f.original.passphrase, stored.passphrase)
            assertEquals(f.original.messageOperationId, stored.messageOperationId)
            assertEquals(f.original.nativeSessionId, stored.nativeSessionId)
            assertEquals(f.original.accessToken, stored.accessToken)
            assertEquals(f.original.refreshToken, stored.refreshToken)
            assertEquals(1, f.writes)
            val reopened = f.repository()
            f.select(reopened)
            assertEquals(
                AppResult.Success(f.target.intent),
                reopened.stageExistingHandover(f.current, f.target),
            )
            assertEquals(stored, f.codec.decode(assertNotNull(f.saved)))
            assertEquals(1, f.writes)
            assertEquals(0, f.newStoreAcquisitions)
            assertEquals(0, f.httpRequests)
            assertFalse(f.leased)
        } finally {
            f.close()
        }
    }

    @Test
    fun missingDirectoriesDatabasesOrCredentialsNeverCreatesAStoreOrIntent() = runTest {
        for (missing in 0..3) {
            val f = ExistingHandoverStorageFixture.create()
            try {
                when (missing) {
                    0 -> f.directoryPresent = false
                    1 -> f.cryptoPresent = false
                    2 -> f.statePresent = false
                    3 -> f.saved = null
                }
                val before = f.saved
                val repo = f.repository()
                f.select(repo)
                assertIs<AppResult.Failed>(repo.stageExistingHandover(f.current, f.target))
                assertEquals(before, f.saved)
                assertEquals(0, f.writes)
                assertEquals(0, f.newStoreAcquisitions)
                assertEquals(0, f.httpRequests)
                assertFalse(f.leased)
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun corruptForeignOrDifferentPrivateIdentityRetainsOriginalCredentials() = runTest {
        for (wrong in 0..2) {
            val f = ExistingHandoverStorageFixture.create()
            try {
                when (wrong) {
                    0 -> f.saved = "not-json"
                    1 -> f.saved = Json.encodeToString(f.original.copy(accountId = SETUP_DEVICE))
                    2 -> f.identity = f.identity.copy(ed25519 = Base64.encode(ByteArray(32) { 8 }))
                }
                val before = f.saved
                val repo = f.repository()
                f.select(repo)
                assertIs<AppResult.Failed>(repo.stageExistingHandover(f.current, f.target))
                assertEquals(before, f.saved)
                assertEquals(0, f.writes)
                assertFalse(f.leased)
                if (wrong < 2) assertFalse("private" in f.events)
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun accountReplacementDuringPrivateReadPreventsPublicationAndReleasesTheGate() = runTest {
        val f = ExistingHandoverStorageFixture.create()
        try {
            val repo = f.repository()
            f.select(repo)
            f.beforePrivateRead = {
                repo.updateRetention(
                    CollaborationRuntimeRetention(f.current.copy(accountId = SETUP_DEVICE), null)
                )
            }
            val before = f.saved
            assertIs<AppResult.Failed>(repo.stageExistingHandover(f.current, f.target))
            assertEquals(before, f.saved)
            assertEquals(0, f.writes)
            assertFalse(f.leased)
            f.beforePrivateRead = {}
            f.select(repo)
            assertEquals(
                AppResult.Success(f.target.intent),
                repo.stageExistingHandover(f.current, f.target),
            )
        } finally {
            f.close()
        }
    }

    @Test
    fun committedButInterruptedWriteRecoversOnlyTheSameIntentWithoutWritingAgain() = runTest {
        val f = ExistingHandoverStorageFixture.create()
        try {
            val repo = f.repository()
            f.select(repo)
            f.afterWrite = { error("fixture interrupted response after commit") }
            assertIs<AppResult.Failed>(repo.stageExistingHandover(f.current, f.target))
            val committed = f.saved
            assertEquals(
                f.target.intent,
                f.codec.handoverIntent(f.codec.decode(assertNotNull(committed))),
            )
            assertFalse(f.leased)
            f.afterWrite = {}
            assertEquals(
                AppResult.Success(f.target.intent),
                repo.stageExistingHandover(f.current, f.target),
            )
            val changed =
                f.target.copy(intent = f.target.intent.copy(operationId = SETUP_OPERATION))
            assertIs<AppResult.Failed>(repo.stageExistingHandover(f.current, changed))
            assertEquals(committed, f.saved)
            assertEquals(1, f.writes)
        } finally {
            f.close()
        }
    }

    @Test
    fun accountReplacementDuringCommittedWriteKeepsOriginalTargetButCannotPublishSuccess() =
        runTest {
            val f = ExistingHandoverStorageFixture.create()
            try {
                val repo = f.repository()
                f.select(repo)
                f.afterWrite = {
                    repo.updateRetention(
                        CollaborationRuntimeRetention(
                            f.current.copy(accountId = SETUP_DEVICE),
                            null,
                        )
                    )
                }
                assertIs<AppResult.Failed>(repo.stageExistingHandover(f.current, f.target))
                assertEquals(
                    f.target.intent,
                    f.codec.handoverIntent(f.codec.decode(assertNotNull(f.saved))),
                )
                assertEquals(1, f.writes)
                assertFalse(f.leased)
            } finally {
                f.close()
            }
        }

    @Test
    fun concurrentAttemptCannotEnterTheDirectoryOwnedByAnEarlierPrivateRead() = runTest {
        val f = ExistingHandoverStorageFixture.create()
        try {
            val repo = f.repository()
            f.select(repo)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforePrivateRead = {
                entered.complete(Unit)
                release.await()
            }
            val first = async { repo.stageExistingHandover(f.current, f.target) }
            entered.await()
            assertTrue(f.leased)
            assertIs<AppResult.Failed>(repo.stageExistingHandover(f.current, f.target))
            assertEquals(1, f.events.count { it == "acquire" })
            release.complete(Unit)
            assertEquals(AppResult.Success(f.target.intent), first.await())
            assertEquals(1, f.writes)
            assertFalse(f.leased)
        } finally {
            f.close()
        }
    }

    @Test
    fun cancellationDuringPrivateReadClosesCredentialsThenDirectoryAndKeepsOriginalRecord() =
        runTest {
            val f = ExistingHandoverStorageFixture.create()
            try {
                val repo = f.repository()
                f.select(repo)
                val entered = CompletableDeferred<Unit>()
                f.beforePrivateRead = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                val before = f.saved
                val request = async { repo.stageExistingHandover(f.current, f.target) }
                entered.await()
                assertTrue(f.leased)
                request.cancelAndJoin()
                assertTrue(request.isCancelled)
                assertEquals(before, f.saved)
                assertEquals(0, f.writes)
                assertEquals(listOf("credential-close", "directory-close"), f.events.takeLast(2))
                assertFalse(f.leased)
                f.beforePrivateRead = {}
                assertEquals(
                    AppResult.Success(f.target.intent),
                    repo.stageExistingHandover(f.current, f.target),
                )
            } finally {
                f.close()
            }
        }

    @Test
    fun cleanupFailuresDoNotReplacePrimaryCancellationOrLeaveTheDirectoryOwned() = runTest {
        val f = ExistingHandoverStorageFixture.create()
        try {
            val repo = f.repository()
            f.select(repo)
            f.beforePrivateRead = { throw CancellationException("fixture primary cancellation") }
            f.beforeCredentialClose = { error("fixture credential close") }
            f.beforeDirectoryClose = { error("fixture directory close") }
            val before = f.saved
            val cancelled = async { repo.stageExistingHandover(f.current, f.target) }
            assertFailsWith<CancellationException> { cancelled.await() }
            assertEquals(before, f.saved)
            assertEquals(0, f.writes)
            assertEquals(listOf("credential-close", "directory-close"), f.events.takeLast(2))
            assertFalse(f.leased)
        } finally {
            f.close()
        }
    }
}

private class ExistingHandoverStorageFixture(
    val scope: CollaborationSdkScope,
    val original: CollaborationSdkRecord,
    var identity: CollaborationSdkIdentityData,
    expectedIdentity: CollaborationLocalDeviceIdentity,
) {
    val server = IdentityServer(SETUP_BASE, "Fixture phone")
    val endpoint = CollaborationSdkEndpoint("https://hris.example.test/")
    val codec = CollaborationSdkRecordCodec(scope)
    val current =
        CollaborationNativeParent(
            "replacement-login",
            setupOwner.audience,
            setupOwner.accountId,
            "99999999-9999-4999-8999-999999999999",
        )
    val target =
        CollaborationExistingRuntimeHandover(
            CollaborationSpaceLegacySdk(setupOwner.setupBinding(), SETUP_DEVICE, expectedIdentity),
            CollaborationRuntimeHandoverIntent(
                SETUP_JOB,
                SETUP_OPERATION,
                "88888888-8888-4888-8888-888888888888",
                1,
                expectedIdentity.fingerprint,
            ),
        )
    var saved: String? = codec.encode(original)
    val events = mutableListOf<String>()
    var writes = 0
    var newStoreAcquisitions = 0
    var httpRequests = 0
    var leased = false
    var directoryPresent = true
    var statePresent = true
    var cryptoPresent = true
    var beforePrivateRead: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    var beforeCredentialClose: () -> Unit = {}
    var beforeDirectoryClose: () -> Unit = {}
    private val http =
        HttpClient(
            MockEngine {
                httpRequests++
                error("Existing handover cannot use HTTP")
            }
        )

    suspend fun select(repo: AndroidCollaborationRuntimeRepository) {
        assertEquals(
            AppResult.Success(Unit),
            repo.updateRetention(
                CollaborationRuntimeRetention(current, current.owner(setupOwner.companyId))
            ),
        )
    }

    fun repository() =
        AndroidCollaborationRuntimeRepository(
            stores =
                object : CollaborationSdkStoreSource {
                    override suspend fun acquire(storeId: String): CollaborationSdkStoreLease {
                        newStoreAcquisitions++
                        error("Existing handover cannot create a store or SDK client")
                    }
                },
            credentialFactory = { name ->
                events += "factory"
                assertTrue(leased)
                assertEquals(scope.credentialRecordName, name)
                object : AtomicCredentialStore {
                    override val persistent = true

                    override suspend fun read(): String? {
                        events += "read"
                        return saved
                    }

                    override suspend fun write(value: String?) {
                        assertTrue(leased)
                        assertNotNull(value)
                        events += "write"
                        writes++
                        saved = value
                        afterWrite()
                    }

                    override suspend fun writeIfAbsent(value: String): String =
                        error("Existing handover cannot enroll")

                    override fun close() {
                        events += "credential-close"
                        beforeCredentialClose()
                    }
                }
            },
            clients = AndroidCollaborationSdkClientSource(),
            cipher = AesGcmContentCipher(),
            server = server,
            endpoint = endpoint,
            http = http,
            inventories =
                object : CollaborationSdkInventorySource {
                    override suspend fun inspect(storeId: String): CollaborationSdkInventoryLease? {
                        events += "acquire"
                        assertEquals(scope.storeId, storeId)
                        assertFalse(leased)
                        if (!directoryPresent) return null
                        leased = true
                        return object : CollaborationSdkInventoryLease {
                            override val storeId = scope.storeId
                            override val databasePath = "/fixture/existing-database"
                            override val stateDatabasePresent = statePresent
                            override val cryptoDatabasePresent = cryptoPresent

                            override fun close() {
                                assertTrue(leased)
                                events += "directory-close"
                                leased = false
                                beforeDirectoryClose()
                            }
                        }
                    }
                },
            privateIdentities =
                CollaborationSdkPrivateIdentitySource { path, passphrase ->
                    assertTrue(leased)
                    assertEquals("/fixture/existing-database", path)
                    assertEquals(original.passphrase, passphrase)
                    events += "private"
                    beforePrivateRead()
                    identity
                },
        )

    fun close() {
        assertFalse(leased)
        http.close()
    }

    companion object {
        suspend fun create(): ExistingHandoverStorageFixture {
            val scope =
                collaborationSdkScope(
                    setupOwner,
                    SETUP_DEVICE,
                    IdentityServer(SETUP_BASE, "Fixture phone"),
                    CollaborationSdkEndpoint("https://hris.example.test/"),
                )
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
            val original =
                codec.initialized(
                    codec.create(command, gateway, Base64.encode(ByteArray(32) { it.toByte() }))
                )
            val identity =
                CollaborationSdkIdentityData(
                    original.matrixUserId,
                    original.matrixDeviceId,
                    Base64.encode(ByteArray(32) { it.toByte() }),
                    Base64.encode(ByteArray(32) { (it + 32).toByte() }),
                )
            return ExistingHandoverStorageFixture(
                scope,
                original,
                identity,
                identity.toLocalDeviceIdentity(),
            )
        }
    }
}
