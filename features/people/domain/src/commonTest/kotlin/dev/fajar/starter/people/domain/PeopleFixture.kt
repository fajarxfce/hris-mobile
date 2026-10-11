package dev.fajar.starter.people.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.people.domain.entities.*
import dev.fajar.starter.people.domain.repositories.PeopleRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.entities.WorkspaceAdmission
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate

class PeopleFixture {
    val session =
        Session(
            "session",
            User("account", "Alex", "alex@example.test"),
            SessionTokens("access", "refresh", Long.MAX_VALUE),
            SessionBinding("family", "https://example.invalid/", Long.MAX_VALUE),
        )
    val workspace =
        Workspace(
            "workspace",
            session.id,
            session.binding.audience,
            CompanyMembership("company", "Company", "CO", "Asia/Jakarta"),
            setOf("people.self.read"),
            Clock.System.now().let {
                WorkspaceAdmission(session.user.id, session.binding.serverId, it, it + 1.hours)
            },
            policy =
                Clock.System.now().let {
                    CompanyPolicySnapshot(
                        CompanyPolicy(
                            null,
                            CompanyModule.entries.toSet(),
                            CompanyMinimumBuilds(0, 0, 0),
                            null,
                            false,
                            it,
                            it + 60.seconds,
                        ),
                        it,
                    )
                },
        )
    val date = LocalDate(2026, 10, 1)
    val employee =
        Employment(
            "employee",
            "company",
            "person",
            "account",
            "E01",
            "Alex",
            null,
            EmploymentContract.Permanent,
            EmploymentStatus.Active,
            LocalDate(2025, 1, 1),
            null,
            0,
            0,
        )
    val directory = SelfEmploymentDirectory(date, "Asia/Jakarta", listOf(employee), null)
    val companyDirectory =
        CompanyDirectory(
            date,
            "Asia/Jakarta",
            listOf(DirectoryEmployee("colleague", "E02", "Sam", "Engineer", "Engineering")),
            null,
        )
    val details = EmploymentDetails(date, employee, null, null, null, null, null)
    val profile = PersonProfile("person", "company", "account", "Alex", null, "ID", null, 0)
    val sessions = Sessions(session)
    val workspaces = Workspaces(workspace)
    var directoryCall: suspend () -> AppResult<SelfEmploymentDirectory> = {
        AppResult.Success(directory)
    }
    var employmentCall: suspend () -> AppResult<EmploymentDetails> = { AppResult.Success(details) }
    var profileCall: suspend () -> AppResult<PersonProfile> = { AppResult.Success(profile) }
    var profileReads = 0
    var directoryReads = 0
    var companyDirectoryReads = 0
    var companyDirectoryCall: suspend (String, String?) -> AppResult<CompanyDirectory> = { _, _ ->
        AppResult.Success(companyDirectory)
    }
    val people =
        object : PeopleRepository {
            override suspend fun companyDirectory(
                sessionId: String,
                companyId: String,
                query: String,
                after: String?,
            ): AppResult<CompanyDirectory> {
                companyDirectoryReads++
                return companyDirectoryCall(query, after)
            }

            override suspend fun selfEmployments(
                sessionId: String,
                companyId: String,
                after: String?,
            ): AppResult<SelfEmploymentDirectory> {
                directoryReads++
                return directoryCall()
            }

            override suspend fun employment(
                sessionId: String,
                companyId: String,
                employeeId: String,
                asOf: LocalDate,
            ) = employmentCall()

            override suspend fun profile(
                sessionId: String,
                companyId: String,
                employeeId: String,
            ): AppResult<PersonProfile> {
                profileReads++
                return profileCall()
            }
        }

    class Sessions(initial: Session) : SessionRepository {
        override val persistent = false
        val state = MutableStateFlow<AppResult<Session?>>(AppResult.Success(initial))

        override fun observe() = state

        override suspend fun current() = state.value

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
                state.compareAndSet(AppResult.Success(expected), AppResult.Success(updated))
            )
    }

    class Workspaces(initial: Workspace) : WorkspaceRepository {
        override suspend fun clientPolicy(sessionId: String, companyId: String) =
            error("Profile reads do not refresh workspace policy.")

        val state = MutableStateFlow<AppResult<Workspace?>>(AppResult.Success(initial))

        override fun observe(sessionId: String) = state

        override suspend fun current(sessionId: String) = state.value

        override suspend fun compareAndSet(
            sessionId: String,
            expected: Workspace?,
            updated: Workspace?,
        ) =
            AppResult.Success(
                state.compareAndSet(AppResult.Success(expected), AppResult.Success(updated))
            )

        override suspend fun permissions(sessionId: String, companyId: String) =
            AppResult.Success(emptySet<String>())
    }
}
