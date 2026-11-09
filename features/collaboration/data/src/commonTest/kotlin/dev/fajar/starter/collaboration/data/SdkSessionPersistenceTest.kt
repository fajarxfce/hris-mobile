package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.CollaborationGatewayDto
import dev.fajar.starter.collaboration.data.mappers.toGateway
import dev.fajar.starter.collaboration.data.repositories.SdkSessionPersistence
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class SdkSessionPersistenceTest {
    @Test
    fun authenticationRejectionSurvivesClosingAndReopeningTheProtectedRecord() = runTest {
        val f = fixture()
        val writer = SdkSessionPersistence(f.store, f.codec, f.initial)
        writer.reportAuthenticationFailure()
        assertEquals(AppResult.Success(Unit), writer.close())
        val retained = f.codec.decode(assertNotNull(f.store.read()))
        assertEquals(f.initial.passphrase, retained.passphrase)
        assertEquals(f.initial.messageOperationId, retained.messageOperationId)
        assertEquals(f.initial.accessToken, retained.accessToken)
        assertEquals(f.initial.refreshToken, retained.refreshToken)
        val reopened = SdkSessionPersistence(f.store, f.codec, retained)
        val rejected = assertIs<AppResult.Failed>(reopened.session())
        assertEquals("client.collaboration_sdk_unauthorized", rejected.failure.code)
        assertEquals(rejected, reopened.retryPending())
        assertEquals(rejected, reopened.markInitialized())
        assertEquals(rejected, save(reopened, retained))
    }

    @Test
    fun failedRejectionPersistenceKeepsTheCredentialOwnerAvailableForClosingRetry() = runTest {
        val f = fixture()
        var failWrites = true
        var closes = 0
        val store =
            object : CredentialStore by f.store {
                override suspend fun write(value: String?) {
                    if (failWrites) error("Fictional storage failure")
                    f.store.write(value)
                }

                override fun close() {
                    closes++
                }
            }
        val writer = SdkSessionPersistence(store, f.codec, f.initial)
        writer.reportAuthenticationFailure()
        assertIs<AppResult.Failed>(writer.close())
        assertEquals(0, closes)
        assertEquals("client.collaboration_sdk_unauthorized", writer.failures.value?.code)
        failWrites = false
        assertEquals(AppResult.Success(Unit), writer.close())
        assertEquals(AppResult.Success(Unit), writer.close())
        assertEquals(1, closes)
        val reopened =
            SdkSessionPersistence(f.store, f.codec, f.codec.decode(assertNotNull(f.store.read())))
        assertEquals(
            "client.collaboration_sdk_unauthorized",
            assertIs<AppResult.Failed>(reopened.session()).failure.code,
        )
    }

    @Test
    fun successfulRotationIsPublishedOnlyAfterTheProtectedWriteCompletes() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store =
            object : CredentialStore by f.store {
                override suspend fun write(value: String?) {
                    entered.complete(Unit)
                    release.await()
                    f.store.write(value)
                }
            }
        val writer = SdkSessionPersistence(store, f.codec, f.initial)
        val saving = async { save(writer, f.initial) }
        entered.await()
        assertFalse(saving.isCompleted)
        assertEquals(f.initial, f.codec.decode(f.store.read()!!))
        release.complete(Unit)
        assertEquals(AppResult.Success(Unit), saving.await())
        val current = assertIs<AppResult.Success<CollaborationSdkRecord>>(writer.snapshot()).value
        assertEquals(newAccess, current.accessToken)
        assertEquals(current, f.codec.decode(f.store.read()!!))
        assertEquals(1, current.credentialRevision)
    }

    @Test
    fun callbackBridgeFailureDuringAWriteCannotBeClearedByItsLateSuccess() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store =
            object : CredentialStore by f.store {
                override suspend fun write(value: String?) {
                    entered.complete(Unit)
                    release.await()
                    f.store.write(value)
                }
            }
        val writer = SdkSessionPersistence(store, f.codec, f.initial)
        val saving = async { save(writer, f.initial) }
        entered.await()
        writer.reportCallbackFailure()
        val denied = assertNotNull(writer.failures.value)
        assertEquals("client.collaboration_callback_failed", denied.code)
        release.complete(Unit)
        assertEquals(AppResult.Failed(denied), saving.await())
        assertEquals(newAccess, f.codec.decode(f.store.read()!!).accessToken)
        assertEquals(AppResult.Failed(denied), writer.snapshot())
        assertEquals(AppResult.Failed(denied), writer.retryPending())
        assertEquals(AppResult.Failed(denied), writer.markInitialized())
        assertEquals(denied, writer.failures.value)
    }

    @Test
    fun failedWritesKeepThePendingTokensAndBlockSnapshotsUntilExplicitRecovery() = runTest {
        val f = fixture()
        var unavailable = true
        val store =
            object : CredentialStore by f.store {
                override suspend fun write(value: String?) {
                    check(!unavailable) { "Fixture storage unavailable" }
                    f.store.write(value)
                }
            }
        val writer = SdkSessionPersistence(store, f.codec, f.initial)
        assertIs<AppResult.Failed>(save(writer, f.initial))
        val failure = writer.failures.value
        assertNotNull(failure)
        assertIs<AppResult.Failed>(writer.snapshot())
        assertIs<AppResult.Failed>(writer.markInitialized())
        assertEquals(f.initial, f.codec.decode(f.store.read()!!))
        unavailable = false
        // A repeated SDK callback does not silently reopen a failed persistence gate.
        assertIs<AppResult.Failed>(save(writer, f.initial))
        assertEquals(failure, writer.failures.value)
        assertEquals(AppResult.Success(Unit), writer.retryPending())
        assertNull(writer.failures.value)
        assertEquals(
            newAccess,
            assertIs<AppResult.Success<CollaborationSdkRecord>>(writer.snapshot()).value.accessToken,
        )
    }

    @Test
    fun cancellationAfterACommittedWriteKeepsTheSamePendingRevisionForRecovery() = runTest {
        val f = fixture()
        val store =
            object : CredentialStore by f.store {
                override suspend fun write(value: String?) {
                    f.store.write(value)
                    currentCoroutineContext().cancel()
                }
            }
        val writer = SdkSessionPersistence(store, f.codec, f.initial)
        val saving = async { save(writer, f.initial) }
        assertFailsWith<CancellationException> { saving.await() }
        val committed = f.codec.decode(f.store.read()!!)
        assertEquals(1, committed.credentialRevision)
        assertNotNull(writer.failures.value)
        assertIs<AppResult.Failed>(writer.snapshot())
        // This fixture's original writer always cancels; a second cancelled recovery still
        // writes the exact same pending revision rather than fabricating another rotation.
        val retry = async { writer.retryPending() }
        assertFailsWith<CancellationException> { retry.await() }
        assertEquals(committed, f.codec.decode(f.store.read()!!))
    }

    @Test
    fun foreignCallbackIdentityCannotOverwriteTheCurrentProtectedSession() = runTest {
        val f = fixture()
        val writer = SdkSessionPersistence(f.store, f.codec, f.initial)
        assertIs<AppResult.Failed>(
            writer.saveTokens(
                "@foreign:matrix.example.test",
                f.initial.matrixDeviceId,
                f.initial.homeserverUrl,
                newAccess,
                newRefresh,
            )
        )
        assertEquals(f.initial, f.codec.decode(f.store.read()!!))
        assertIs<AppResult.Failed>(writer.retryPending())
        assertIs<AppResult.Failed>(writer.snapshot())
    }

    @Test
    fun closingWaitsForTheWriteOwnerAndRejectsLateCallbacks() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store =
            object : CredentialStore by f.store {
                override suspend fun write(value: String?) {
                    entered.complete(Unit)
                    release.await()
                    f.store.write(value)
                }
            }
        val writer = SdkSessionPersistence(store, f.codec, f.initial)
        val saving = async { save(writer, f.initial) }
        entered.await()
        val closeEntered = CompletableDeferred<Unit>()
        val closing = async {
            closeEntered.complete(Unit)
            writer.close()
        }
        closeEntered.await()
        assertFalse(closing.isCompleted)
        release.complete(Unit)
        assertEquals(AppResult.Success(Unit), saving.await())
        closing.await()
        val retained = f.store.read()
        assertIs<AppResult.Failed>(save(writer, f.initial))
        assertIs<AppResult.Failed>(writer.snapshot())
        assertEquals(retained, f.store.read())
    }

    @Test
    fun initializationNeedsAProtectedWriteAndDoesNotInventAnotherTokenRotation() = runTest {
        val f = fixture()
        var writes = 0
        val store =
            object : CredentialStore by f.store {
                override suspend fun write(value: String?) {
                    writes++
                    f.store.write(value)
                }
            }
        val writer = SdkSessionPersistence(store, f.codec, f.initial)
        assertEquals(AppResult.Success(Unit), writer.markInitialized())
        assertEquals(AppResult.Success(Unit), writer.markInitialized())
        assertEquals(1, writes)
        val retained = f.codec.decode(f.store.read()!!)
        assertTrue(retained.initialized)
        assertEquals(f.initial.accessToken, retained.accessToken)
        assertEquals(f.initial.passphrase, retained.passphrase)
        assertEquals(1, retained.credentialRevision)
    }

    @Test
    fun rejectedAuthenticationPreservesLocalOwnershipAndCannotBeClearedByStorageRetry() = runTest {
        val f = fixture()
        val writer = SdkSessionPersistence(f.store, f.codec, f.initial)
        writer.reportAuthenticationFailure()
        val rejected = assertNotNull(writer.failures.value)
        assertEquals(FailureKind.Unauthorized, rejected.kind)
        assertEquals("client.collaboration_sdk_unauthorized", rejected.code)
        assertFalse(
            dev.fajar.starter.collaboration.domain.policies.collaborationContentResponseClosesRead(
                rejected
            )
        )
        assertEquals(AppResult.Failed(rejected), writer.snapshot())
        assertEquals(AppResult.Failed(rejected), writer.retryPending())
        assertEquals(AppResult.Failed(rejected), writer.markInitialized())
        assertEquals(AppResult.Failed(rejected), save(writer, f.initial))
        assertEquals(f.initial, f.codec.decode(f.store.read()!!))
    }

    @Test
    fun knownAuthenticationRejectionSurvivesAnEarlierOrLaterStorageCallbackFailure() = runTest {
        val f = fixture()
        val writer = SdkSessionPersistence(f.store, f.codec, f.initial)
        writer.reportCallbackFailure()
        assertEquals(FailureKind.Storage, writer.failures.value?.kind)
        writer.reportAuthenticationFailure()
        val rejected = assertNotNull(writer.failures.value)
        writer.reportCallbackFailure()
        assertEquals(rejected, writer.failures.value)
        assertEquals(AppResult.Failed(rejected), writer.retryPending())
        assertEquals("client.collaboration_sdk_unauthorized", rejected.code)
        assertEquals(f.initial, f.codec.decode(f.store.read()!!))
    }

    @Test
    fun lateCredentialWriteSuccessCannotReopenRejectedAuthentication() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store =
            object : CredentialStore by f.store {
                override suspend fun write(value: String?) {
                    entered.complete(Unit)
                    release.await()
                    f.store.write(value)
                }
            }
        val writer = SdkSessionPersistence(store, f.codec, f.initial)
        val saving = async { save(writer, f.initial) }
        entered.await()
        writer.reportAuthenticationFailure()
        val rejected = assertNotNull(writer.failures.value)
        release.complete(Unit)
        assertEquals(AppResult.Failed(rejected), saving.await())
        assertEquals(newAccess, f.codec.decode(f.store.read()!!).accessToken)
        assertEquals(AppResult.Failed(rejected), writer.snapshot())
        assertEquals(AppResult.Failed(rejected), writer.retryPending())
        assertEquals(rejected, writer.failures.value)
    }

    private suspend fun save(writer: SdkSessionPersistence, initial: CollaborationSdkRecord) =
        writer.saveTokens(
            initial.matrixUserId,
            initial.matrixDeviceId,
            initial.homeserverUrl,
            newAccess,
            newRefresh,
        )

    private suspend fun fixture(): Fixture {
        val scope =
            collaborationSdkScope(
                setupOwner,
                SETUP_DEVICE,
                IdentityServer(SETUP_BASE, "Fictional"),
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
        val initial = codec.create(command, gateway, Base64.encode(ByteArray(32) { it.toByte() }))
        return Fixture(
            codec,
            initial,
            MemoryCredentialStore().also { it.write(codec.encode(initial)) },
        )
    }

    private data class Fixture(
        val codec: CollaborationSdkRecordCodec,
        val initial: CollaborationSdkRecord,
        val store: MemoryCredentialStore,
    )

    private companion object {
        val newAccess = "hris_mx_a_" + "c".repeat(43)
        val newRefresh = "hris_mx_r_" + "d".repeat(43)
    }
}
