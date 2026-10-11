@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workspace.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.entities.WorkspaceAdmission
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.*
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class WorkspaceSelectionTest {
    private val session =
        Session(
            "account-session",
            User("account", "Alex", "alex@example.com"),
            SessionTokens("access", "refresh", Long.MAX_VALUE),
            SessionBinding("family", "https://example.invalid/", Long.MAX_VALUE),
        )
    private val companies =
        listOf(
            CompanyMembership("one", "Company One", "ONE", "Asia/Jakarta"),
            CompanyMembership("two", "Company Two", "TWO", "Asia/Jakarta"),
        )

    private fun account(required: Boolean = false) =
        AccountSnapshot(
            session.user,
            companies,
            emptySet(),
            SessionAssurance(required, false, false, null, null),
            mfaConfigured = false,
        )

    private class Sessions(initial: Session) : SessionRepository {
        override val persistent = false
        val record = MutableStateFlow<AppResult<Session?>>(AppResult.Success(initial))

        override fun observe() = record

        override suspend fun current() = record.value

        override suspend fun clear(sessionId: String): AppResult<Boolean> {
            val session =
                when (val result = current()) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
            return if (session?.id == sessionId) compareAndSet(session, null)
            else AppResult.Success(false)
        }

        override suspend fun compareAndSet(expected: Session?, updated: Session?) =
            AppResult.Success(
                record.compareAndSet(AppResult.Success(expected), AppResult.Success(updated))
            )
    }

    private class Workspaces : WorkspaceRepository {
        val selected = MutableStateFlow<AppResult<Workspace?>>(AppResult.Success(null))
        var readAccess: suspend (String) -> AppResult<Set<String>> = {
            AppResult.Success(setOf("people.self.read"))
        }
        var requests = 0

        override fun observe(sessionId: String) = selected

        override suspend fun current(sessionId: String) = selected.value

        override suspend fun compareAndSet(
            sessionId: String,
            expected: Workspace?,
            updated: Workspace?,
        ) =
            AppResult.Success(
                selected.compareAndSet(AppResult.Success(expected), AppResult.Success(updated))
            )

        override suspend fun permissions(
            sessionId: String,
            companyId: String,
        ): AppResult<Set<String>> {
            requests++
            return readAccess(companyId)
        }
    }

    private class Accounts(var value: AccountSnapshot) : AccountRepository {
        override suspend fun load(sessionId: String) = AppResult.Success(value)
    }

    @Test
    fun selectionRequiresLiveMembershipAndMfaBeforeReadingCompanyAccess() = runTest {
        val workspaces = Workspaces()
        val accounts = Accounts(account(required = true))
        val select = SelectWorkspace(Sessions(session), accounts, workspaces)
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(select(session.id, "one")).failure.code,
        )
        accounts.value = account()
        assertEquals(
            "company_access_denied",
            assertIs<AppResult.Failed>(select(session.id, "absent")).failure.code,
        )
        assertEquals(0, workspaces.requests)
        assertEquals(AppResult.Success(null), workspaces.current(session.id))
    }

    @Test
    fun delayedSelectionCannotReplaceANewerChoiceAndReturningCreatesAFreshScope() = runTest {
        val workspaces = Workspaces()
        val release = CompletableDeferred<Unit>()
        workspaces.readAccess = {
            if (it == "one") release.await()
            AppResult.Success(emptySet())
        }
        val select = SelectWorkspace(Sessions(session), Accounts(account()), workspaces)
        val first = async { select(session.id, "one") }
        runCurrent()
        val second = assertIs<AppResult.Success<Workspace>>(select(session.id, "two")).value
        release.complete(Unit)
        assertEquals(
            "client.workspace_changed",
            assertIs<AppResult.Failed>(first.await()).failure.code,
        )
        assertEquals(AppResult.Success(second), workspaces.current(session.id))
        val returned = assertIs<AppResult.Success<Workspace>>(select(session.id, "two")).value
        assertNotEquals(second.id, returned.id)
    }

    @Test
    fun logoutOrCancellationDuringAccessLookupCannotAdmitTheOldCompany() = runTest {
        for (cancel in listOf(false, true)) {
            val sessions = Sessions(session)
            val workspaces = Workspaces()
            val release = CompletableDeferred<Unit>()
            workspaces.readAccess = {
                withContext(NonCancellable) { release.await() }
                AppResult.Success(emptySet())
            }
            val pending = async {
                SelectWorkspace(sessions, Accounts(account()), workspaces)(session.id, "one")
            }
            runCurrent()
            if (cancel) pending.cancel() else sessions.compareAndSet(session, null)
            release.complete(Unit)
            if (cancel) assertFailsWith<CancellationException> { pending.await() }
            else assertIs<AppResult.Failed>(pending.await())
            assertEquals(AppResult.Success(null), workspaces.current(session.id))
        }
    }

    @Test
    fun observersNeverPublishAnotherSessionOrAudiencesCompany() = runTest {
        val sessions = Sessions(session)
        val workspaces = Workspaces()
        val observe = ObserveWorkspace(sessions, workspaces)
        val selected =
            Workspace(
                "scope",
                session.id,
                session.binding.audience,
                companies.first(),
                emptySet(),
                Clock.System.now().let {
                    WorkspaceAdmission(session.user.id, session.binding.serverId, it, it + 1.hours)
                },
            )
        workspaces.selected.value = AppResult.Success(selected)
        assertEquals(AppResult.Success(selected), observe().first())
        sessions.compareAndSet(session, session.copy(id = "another-session"))
        assertEquals(AppResult.Success(null), observe().first())
        sessions.record.value =
            AppResult.Success(
                session.copy(binding = session.binding.copy(audience = "https://other.invalid/"))
            )
        assertEquals(AppResult.Success(null), observe().first())
    }
}
