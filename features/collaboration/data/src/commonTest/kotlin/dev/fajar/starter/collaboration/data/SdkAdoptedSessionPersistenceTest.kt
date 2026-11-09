package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.securestorage.CredentialStore
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SdkAdoptedSessionPersistenceTest {
    @Test
    fun adoptedRejectionPersistsWithoutChangingTheOriginalNamespaceOrReplayingItAway() = runTest {
        val f = SdkAdoptionFixture.create()
        f.store.write(f.codec.encode(f.adopted))
        val namespace = SdkCredentialNamespace.Adopted(f.command.id)
        val writer = SdkSessionPersistence(f.store, f.codec, f.adopted, namespace)
        writer.reportAuthenticationFailure()
        assertEquals(AppResult.Success(Unit), writer.close())
        val retained = f.codec.decode(assertNotNull(f.store.read()))
        assertTrue(assertNotNull(retained.adoption).authenticationRejected)
        assertFalse(retained.authenticationRejected)
        assertEquals(f.adopted.accessToken, retained.accessToken)
        assertEquals(f.adopted.passphrase, retained.passphrase)
        assertEquals(f.adopted.messageOperationId, retained.messageOperationId)
        assertEquals(retained, f.codec.stageAdoption(retained, f.command, f.gateway))
        val reopened = SdkSessionPersistence(f.store, f.codec, retained, namespace)
        assertEquals(
            "client.collaboration_sdk_unauthorized",
            assertIs<AppResult.Failed>(reopened.session()).failure.code,
        )
    }

    @Test
    fun originalRejectionDoesNotRejectSeparateAdmittedAdoptedCredentials() = runTest {
        val f = SdkAdoptionFixture.create()
        val retained = f.adopted.copy(authenticationRejected = true)
        f.store.write(f.codec.encode(retained))
        val writer =
            SdkSessionPersistence(
                f.store,
                f.codec,
                retained,
                SdkCredentialNamespace.Adopted(f.command.id),
            )
        assertNull(writer.failures.value)
        assertIs<AppResult.Success<*>>(writer.session())
        assertEquals(AppResult.Success(Unit), writer.close())
        assertEquals(retained, f.codec.decode(assertNotNull(f.store.read())))
    }

    @Test
    fun onlyTheExplicitAdoptedLeaseReadsAndUpdatesItsNewCredentials() = runTest {
        val f = SdkAdoptionFixture.create()
        f.store.write(f.codec.encode(f.adopted))
        val original = SdkSessionPersistence(f.store, f.codec, f.adopted)
        assertIs<AppResult.Failed>(original.session())
        val foreign =
            SdkSessionPersistence(
                f.store,
                f.codec,
                f.adopted,
                SdkCredentialNamespace.Adopted(SETUP_OPERATION),
            )
        assertIs<AppResult.Failed>(foreign.session())
        val writer =
            SdkSessionPersistence(
                f.store,
                f.codec,
                f.adopted,
                SdkCredentialNamespace.Adopted(f.command.id),
            )
        val first = assertIs<AppResult.Success<SdkSessionCredentials>>(writer.session()).value
        assertEquals(f.gateway.accessToken, first.accessToken)
        assertNotEquals(f.original.accessToken, first.accessToken)
        assertIs<AppResult.Success<*>>(
            writer.saveTokens(
                first.matrixUserId,
                first.matrixDeviceId,
                first.homeserverUrl,
                newAccess,
                newRefresh,
            )
        )
        assertIs<AppResult.Success<*>>(writer.markInitialized())
        val record = f.codec.decode(assertNotNull(f.store.read()))
        assertEquals(f.original.accessToken, record.accessToken)
        assertEquals(f.original.messageOperationId, record.messageOperationId)
        assertEquals(newAccess, record.adoption?.accessToken)
        assertTrue(assertNotNull(record.adoption).initialized)
        writer.close()
        assertIs<AppResult.Failed>(writer.session())
        assertIs<AppResult.Failed>(
            writer.saveTokens(
                first.matrixUserId,
                first.matrixDeviceId,
                first.homeserverUrl,
                newAccess,
                newRefresh,
            )
        )
    }

    @Test
    fun failedWriteKeepsTheNewPairPendingWithoutExposingOldOrUncommittedCredentials() = runTest {
        val f = SdkAdoptionFixture.create()
        f.store.write(f.codec.encode(f.adopted))
        var failed = true
        val store =
            object : CredentialStore by f.store {
                override suspend fun write(value: String?) {
                    if (failed) error("fixture unavailable")
                    f.store.write(value)
                }
            }
        val writer =
            SdkSessionPersistence(
                store,
                f.codec,
                f.adopted,
                SdkCredentialNamespace.Adopted(f.command.id),
            )
        assertIs<AppResult.Failed>(
            writer.saveTokens(
                f.original.matrixUserId,
                f.original.matrixDeviceId,
                f.original.homeserverUrl,
                newAccess,
                newRefresh,
            )
        )
        assertIs<AppResult.Failed>(writer.session())
        assertEquals(f.adopted, f.codec.decode(assertNotNull(f.store.read())))
        failed = false
        assertIs<AppResult.Success<*>>(writer.retryPending())
        assertEquals(
            newAccess,
            assertIs<AppResult.Success<SdkSessionCredentials>>(writer.session()).value.accessToken,
        )
        assertEquals(
            f.original.accessToken,
            f.codec.decode(assertNotNull(f.store.read())).accessToken,
        )
        writer.close()
    }

    @Test
    fun cancelledWriteRetainsExactPairAndRecoveryDoesNotRetargetTheAdoption() = runTest {
        val f = SdkAdoptionFixture.create()
        f.store.write(f.codec.encode(f.adopted))
        val entered = CompletableDeferred<Unit>()
        var hold = true
        val store =
            object : CredentialStore by f.store {
                override suspend fun write(value: String?) {
                    if (hold) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                    f.store.write(value)
                }
            }
        val writer =
            SdkSessionPersistence(
                store,
                f.codec,
                f.adopted,
                SdkCredentialNamespace.Adopted(f.command.id),
            )
        val task = async {
            writer.saveTokens(
                f.original.matrixUserId,
                f.original.matrixDeviceId,
                f.original.homeserverUrl,
                newAccess,
                newRefresh,
            )
        }
        entered.await()
        task.cancelAndJoin()
        assertIs<AppResult.Failed>(writer.session())
        assertEquals(f.adopted, f.codec.decode(assertNotNull(f.store.read())))
        hold = false
        assertIs<AppResult.Success<*>>(writer.retryPending())
        val stored = f.codec.decode(assertNotNull(f.store.read()))
        assertEquals(f.command.id, stored.adoption?.bootstrapOperationId)
        assertEquals(newAccess, stored.adoption?.accessToken)
        assertEquals(f.target, f.codec.handoverIntent(stored))
        writer.close()
    }

    @Test
    fun wrongCallbackIdentityAndTerminalCallbackFailureCannotClearTheLeaseFailure() = runTest {
        val f = SdkAdoptionFixture.create()
        f.store.write(f.codec.encode(f.adopted))
        val writer =
            SdkSessionPersistence(
                f.store,
                f.codec,
                f.adopted,
                SdkCredentialNamespace.Adopted(f.command.id),
            )
        assertIs<AppResult.Failed>(
            writer.saveTokens(
                "@wrong:example.test",
                f.original.matrixDeviceId,
                f.original.homeserverUrl,
                newAccess,
                newRefresh,
            )
        )
        assertIs<AppResult.Failed>(writer.session())
        writer.reportCallbackFailure()
        assertIs<AppResult.Failed>(writer.retryPending())
        assertEquals(f.adopted, f.codec.decode(assertNotNull(f.store.read())))
        writer.close()
    }

    private companion object {
        val newAccess = "hris_mxg_a_" + "e".repeat(43)
        val newRefresh = "hris_mxg_r_" + "f".repeat(43)
    }
}
