@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.identity.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.AccountCacheStore
import dev.fajar.starter.identity.data.datasources.MemorySessionDataSource
import dev.fajar.starter.identity.data.repositories.StoredSessionRepository
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.identity.domain.usecases.SignOut
import dev.fajar.starter.securestorage.CredentialStore
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class StoredSessionRepositoryTest {
    private class Vault : CredentialStore {
        override val persistent = true
        var value: String? = null
        var fail = false
        var beforeWrite: suspend (String?) -> Unit = {}

        override suspend fun read() = value

        override suspend fun write(value: String?) {
            check(!fail) { "private SDK error" }
            beforeWrite(value)
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
            binding =
                dev.fajar.starter.identity.domain.entities.SessionBinding(
                    "11111111-1111-4111-8111-111111111111",
                    "https://example.invalid/",
                    Long.MAX_VALUE,
                ),
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
    fun pendingRefreshIntentSurvivesSecureStoreAndRepositoryRecreation() = runTest {
        val vault = Vault()
        val prepared =
            session.copy(pendingRefreshOperationId = "11111111-1111-4111-8111-111111111111")
        val first = StoredSessionRepository(vault, MemorySessionDataSource(), Accounts())
        assertEquals(AppResult.Success(true), first.compareAndSet(null, prepared))
        val restored = StoredSessionRepository(vault, MemorySessionDataSource(), Accounts())
        assertEquals(AppResult.Success(prepared), restored.current())
        assertFalse(prepared.toString().contains("refresh"))
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
            assertIs<AppResult.Failed>(repository.clear(session.id)).failure.kind,
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

    @Test
    fun logoutClearsTheSameIdentityAfterRotationAndRejectsLateCredentialPublication() = runTest {
        val vault = Vault()
        val accounts = Accounts()
        val repository = StoredSessionRepository(vault, MemorySessionDataSource(), accounts)
        repository.compareAndSet(null, session)
        val refreshed =
            session.copy(tokens = SessionTokens("new-access", "new-refresh", Long.MAX_VALUE))
        repository.compareAndSet(session, refreshed)
        val admittedBeforeRotation =
            object : SessionRepository by repository {
                override suspend fun current() = AppResult.Success(session)
            }
        assertEquals(AppResult.Success(Unit), SignOut(admittedBeforeRotation)())
        assertNull(vault.value)
        assertNull(accounts.active)
        assertEquals(AppResult.Success(null), repository.current())
        assertEquals(AppResult.Success(false), repository.compareAndSet(refreshed, session))
    }

    @Test
    fun anOldLogoutCannotClearTheReplacementAccount() = runTest {
        val repository = StoredSessionRepository(Vault(), MemorySessionDataSource(), Accounts())
        repository.compareAndSet(null, session)
        val replacement =
            session.copy(id = "replacement", user = User("2", "Blair", "blair@example.test"))
        repository.compareAndSet(session, replacement)
        assertEquals(AppResult.Success(false), repository.clear(session.id))
        assertEquals(AppResult.Success(replacement), repository.current())
    }

    @Test
    fun cancellationAfterVaultDeletionStartsStillFinishesPublicationAndCacheCleanup() = runTest {
        val vault = Vault()
        val accounts = Accounts()
        val repository = StoredSessionRepository(vault, MemorySessionDataSource(), accounts)
        repository.compareAndSet(null, session)
        val release = CompletableDeferred<Unit>()
        vault.beforeWrite = { value -> if (value == null) release.await() }
        val clearing = launch { repository.clear(session.id) }
        runCurrent()
        clearing.cancel()
        release.complete(Unit)
        clearing.join()
        assertNull(vault.value)
        assertNull(accounts.active)
        assertEquals(AppResult.Success(null), repository.current())
        assertEquals(
            AppResult.Success(null),
            StoredSessionRepository(vault, MemorySessionDataSource(), accounts).current(),
        )
    }

    @Test
    fun cancellingBeforeTheCommitGuardDoesNotDeleteAnIdentity() = runTest {
        val vault = Vault()
        val repository = StoredSessionRepository(vault, MemorySessionDataSource(), Accounts())
        repository.compareAndSet(null, session)
        val release = CompletableDeferred<Unit>()
        vault.beforeWrite = { release.await() }
        val refreshed =
            session.copy(tokens = SessionTokens("new-access", "new-refresh", Long.MAX_VALUE))
        val rotation = launch { repository.compareAndSet(session, refreshed) }
        runCurrent()
        val clearing = launch { repository.clear(session.id) }
        runCurrent()
        clearing.cancelAndJoin()
        release.complete(Unit)
        rotation.join()
        assertEquals(AppResult.Success(refreshed), repository.current())
        assertEquals(AppResult.Success(true), repository.clear(session.id))
    }
}
