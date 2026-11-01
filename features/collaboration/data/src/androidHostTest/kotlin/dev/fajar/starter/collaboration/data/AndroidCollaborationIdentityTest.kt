package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.dto.CollaborationGatewayDto
import dev.fajar.starter.collaboration.data.mappers.toGateway
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeRepository
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.collaboration.domain.entities.*
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
        val values = mutableMapOf<String, String>()
        var persistent = true
        var reads = 0
        var writes = 0
        var closes = 0
        var beforeRead: suspend () -> Unit = {}
        private val http =
            createHttpClient(MockEngine { error("No identity network request") }, endpoint.baseUrl)
        val runtime =
            AndroidCollaborationRuntimeRepository(
                object : CollaborationSdkStoreSource {
                    override suspend fun acquire(storeId: String): CollaborationSdkStoreLease =
                        error("No identity directory acquisition")
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
            )

        suspend fun store(initialized: Boolean = true, expired: Boolean = false) {
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
            values[scope.credentialRecordName] = codec.encode(record)
        }

        fun close() {
            http.cancelAndClose()
        }
    }
}
