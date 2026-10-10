package dev.fajar.starter.identity.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.AccountCacheStore
import dev.fajar.starter.identity.data.datasources.MemorySessionDataSource
import dev.fajar.starter.identity.data.repositories.StoredSessionRepository
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.securestorage.CredentialStore
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class StoredSessionRepositoryTest {
    private class Vault : CredentialStore {
        override val persistent = true
        var value: String? = null
        var fail = false

        override suspend fun read() = value

        override suspend fun write(value: String?) {
            check(!fail) { "private SDK error" }
            this.value = value
        }
    }

    private class Accounts : AccountCacheStore {
        var active: String? = null

        override suspend fun activate(sessionId: String?) {
            active = sessionId
        }
    }

    private val session =
        Session(
            "a",
            User("1", "Alex", "demo@example.com"),
            SessionTokens("access", "refresh", Long.MAX_VALUE),
        )

    @Test
    fun restartRestoresIdentityAndLogoutClearsTheVaultAndCacheScope() = runTest {
        val vault = Vault()
        val accounts = Accounts()
        var repository = StoredSessionRepository(vault, MemorySessionDataSource(), accounts)
        assertEquals(AppResult.Success(true), repository.compareAndSet(null, session))
        repository = StoredSessionRepository(vault, MemorySessionDataSource(), accounts)
        assertEquals(AppResult.Success(session), repository.current())
        assertEquals("a", accounts.active)
        assertEquals(
            AppResult.Success(false),
            repository.compareAndSet(session.copy(id = "stale"), null),
        )
        assertEquals(AppResult.Success(true), repository.compareAndSet(session, null))
        assertNull(vault.value)
        assertNull(accounts.active)
    }

    @Test
    fun vaultFailureRetainsIdentityAndNextReadReconcilesDerivativeCache() = runTest {
        val vault = Vault()
        val accounts = Accounts()
        val repository = StoredSessionRepository(vault, MemorySessionDataSource(), accounts)
        repository.compareAndSet(null, session)
        vault.fail = true
        assertEquals(
            FailureKind.Storage,
            assertIs<AppResult.Failed>(repository.compareAndSet(session, null)).failure.kind,
        )
        assertEquals(AppResult.Success(session), repository.current())
        assertEquals("a", accounts.active)
    }

    @Test
    fun corruptCredentialsFailExplicitlyWithoutPublishingOrSilentlySigningOut() = runTest {
        val vault = Vault().apply { value = "invalid private content" }
        val repository = StoredSessionRepository(vault, MemorySessionDataSource(), Accounts())
        assertIs<AppResult.Failed>(repository.current())
        assertEquals("invalid private content", vault.value)
    }
}
