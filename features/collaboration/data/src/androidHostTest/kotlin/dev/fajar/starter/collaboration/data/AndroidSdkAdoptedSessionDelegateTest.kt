package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.SlidingSyncVersion

class AndroidSdkAdoptedSessionDelegateTest {
    @Test
    fun nativeCallbackReadsOnlyAdoptedTokensWithOriginalMatrixIdentity() = runTest {
        val f = SdkAdoptionFixture.create()
        f.store.write(f.codec.encode(f.adopted))
        val persistence =
            SdkSessionPersistence(
                f.store,
                f.codec,
                f.adopted,
                SdkCredentialNamespace.Adopted(f.command.id),
            )
        val delegate = AndroidSdkSessionDelegate(persistence)
        try {
            val session = delegate.retrieveSessionFromKeychain(f.original.matrixUserId)
            assertEquals(f.gateway.accessToken, session.accessToken)
            assertEquals(f.gateway.refreshToken, session.refreshToken)
            assertEquals(f.original.matrixDeviceId, session.deviceId)
            assertEquals(f.original.homeserverUrl, session.homeserverUrl)
            assertEquals(SlidingSyncVersion.NONE, session.slidingSyncVersion)
            assertNull(session.oauthData)
            delegate.saveSessionInKeychain(
                session.copy(
                    accessToken = "hris_mxg_a_" + "e".repeat(43),
                    refreshToken = "hris_mxg_r_" + "f".repeat(43),
                )
            )
            val record = f.codec.decode(assertNotNull(f.store.read()))
            assertEquals(f.original.accessToken, record.accessToken)
            assertEquals("hris_mxg_a_" + "e".repeat(43), record.adoption?.accessToken)
            assertFailsWith<ClientException> {
                delegate.retrieveSessionFromKeychain("@wrong:example.test")
            }
        } finally {
            persistence.close()
        }
        assertFailsWith<ClientException> {
            delegate.retrieveSessionFromKeychain(f.original.matrixUserId)
        }
    }

    @Test
    fun originalNativeMappingStillRejectsEveryHandoverIncludingAdoption() = runTest {
        val f = SdkAdoptionFixture.create()
        assertEquals(f.original.accessToken, f.original.toNativeSession().accessToken)
        assertFailsWith<IllegalStateException> { f.staged.toNativeSession() }
        assertFailsWith<IllegalStateException> { f.adopted.toNativeSession() }
    }

    @Test
    fun wrongTokenNamespaceNeverOverwritesTheProtectedAdoption() = runTest {
        val f = SdkAdoptionFixture.create()
        f.store.write(f.codec.encode(f.adopted))
        val persistence =
            SdkSessionPersistence(
                f.store,
                f.codec,
                f.adopted,
                SdkCredentialNamespace.Adopted(f.command.id),
            )
        val delegate = AndroidSdkSessionDelegate(persistence)
        try {
            val session = delegate.retrieveSessionFromKeychain(f.original.matrixUserId)
            delegate.saveSessionInKeychain(
                session.copy(
                    accessToken = f.original.accessToken,
                    refreshToken = f.original.refreshToken,
                )
            )
            assertNotNull(persistence.failures.value)
            assertEquals(f.adopted, f.codec.decode(assertNotNull(f.store.read())))
            assertFailsWith<ClientException> {
                delegate.retrieveSessionFromKeychain(f.original.matrixUserId)
            }
        } finally {
            persistence.close()
        }
    }
}
