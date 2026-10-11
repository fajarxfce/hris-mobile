package dev.fajar.starter.workforce.domain

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.*
import dev.fajar.starter.workforce.domain.repositories.*
import dev.fajar.starter.workforce.domain.usecases.*
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

class WorkCalendarFixture(instant: () -> Instant = { Instant.parse("2026-10-12T01:00:00Z") }) {
    val clock =
        object : Clock {
            override fun now() = instant()
        }
    val session =
        Session(
            "session",
            User("11111111-1111-1111-1111-111111111111", "Alex", "alex@example.test"),
            SessionTokens("access", "refresh", Long.MAX_VALUE),
            SessionBinding(
                "family",
                "https://hris.example.test/",
                (clock.now() + 24.hours).toEpochMilliseconds(),
            ),
        )
    val policy =
        CompanyPolicy(
            null,
            CompanyModule.entries.toSet(),
            CompanyMinimumBuilds(0, 0, 0),
            null,
            false,
            clock.now(),
            clock.now() + 60.seconds,
        )
    val workspace =
        Workspace(
            "workspace",
            session.id,
            session.binding.audience,
            CompanyMembership(
                "22222222-2222-2222-2222-222222222222",
                "Company",
                "CO",
                "Asia/Jakarta",
            ),
            setOf("attendance.self.record"),
            WorkspaceAdmission(
                session.user.id,
                session.binding.serverId,
                clock.now(),
                clock.now() + 1.hours,
            ),
            policy = CompanyPolicySnapshot(policy, clock.now()),
        )
    val build = AppBuild(AppPlatform.Android, 1, "1.0", null)
    val sessionState = MutableStateFlow<AppResult<Session?>>(AppResult.Success(session))
    val workspaceState = MutableStateFlow<AppResult<Workspace?>>(AppResult.Success(workspace))
    val records = MutableStateFlow<AppResult<WorkCalendarSnapshot?>>(AppResult.Success(null))
    val employee =
        WorkforceEmployment(
            "33333333-3333-3333-3333-333333333333",
            "E01",
            WorkforceEmploymentStatus.ACTIVE,
            0,
        )
    val directory =
        WorkforceDirectory(
            session.user.id,
            workspace.company.id,
            LocalDate(2026, 10, 12),
            "Asia/Jakarta",
            listOf(employee),
            null,
        )
    var policyCall: suspend () -> AppResult<CompanyPolicy> = {
        AppResult.Success(
            policy.copy(serverTime = clock.now(), validUntil = clock.now() + 60.seconds)
        )
    }
    var directoryCall: suspend () -> AppResult<WorkforceDirectory> = {
        AppResult.Success(directory)
    }
    var calendarCall: suspend (CalendarPeriod) -> AppResult<WorkCalendar> = { period ->
        AppResult.Success(
            WorkCalendar(
                null,
                List(7) { WorkDay.Unassigned(period.from.plus(it, DateTimeUnit.DAY)) },
            )
        )
    }
    var policyReads = 0
    var directoryReads = 0
    var calendarReads = 0
    var writes = 0
    var clears = 0
    var observers = 0
    var rejectWrite = false
    val sessions =
        object : SessionRepository {
            override val persistent = false

            override fun observe() = sessionState

            override suspend fun current() = sessionState.value

            override suspend fun clear(sessionId: String) =
                AppResult.Success(
                    sessionState.compareAndSet(AppResult.Success(session), AppResult.Success(null))
                )

            override suspend fun compareAndSet(expected: Session?, updated: Session?) =
                AppResult.Success(
                    sessionState.compareAndSet(
                        AppResult.Success(expected),
                        AppResult.Success(updated),
                    )
                )
        }
    val workspaces =
        object : WorkspaceRepository {
            override fun observe(sessionId: String) = workspaceState

            override suspend fun current(sessionId: String) = workspaceState.value

            override suspend fun compareAndSet(
                sessionId: String,
                expected: Workspace?,
                updated: Workspace?,
            ) =
                AppResult.Success(
                    workspaceState.compareAndSet(
                        AppResult.Success(expected),
                        AppResult.Success(updated),
                    )
                )

            override suspend fun permissions(sessionId: String, companyId: String) =
                AppResult.Success(workspace.permissions)

            override suspend fun clientPolicy(
                sessionId: String,
                companyId: String,
            ): AppResult<CompanyPolicy> {
                policyReads++
                return policyCall()
            }
        }
    val remote =
        object : WorkforceRepository {
            override suspend fun selfEmployments(
                access: CalendarAccess,
                after: String?,
            ): AppResult<WorkforceDirectory> {
                directoryReads++
                return directoryCall()
            }

            override suspend fun calendar(
                access: CalendarAccess,
                employeeId: String,
                period: CalendarPeriod,
            ): AppResult<WorkCalendar> {
                calendarReads++
                return calendarCall(period)
            }
        }
    val cache =
        object : WorkCalendarCacheRepository {
            override fun observe(access: CalendarAccess): Flow<AppResult<WorkCalendarSnapshot?>> =
                flow {
                    observers++
                    try {
                        emitAll(records)
                    } finally {
                        observers--
                    }
                }

            override suspend fun generation(access: CalendarAccess) =
                AppResult.Success((records.value as? AppResult.Success)?.value?.generation)

            override suspend fun replace(
                access: CalendarAccess,
                expectedGeneration: String?,
                snapshot: WorkCalendarSnapshot?,
            ): AppResult<Boolean> {
                val current = (records.value as? AppResult.Success)?.value
                if (rejectWrite || expectedGeneration != current?.generation)
                    return AppResult.Success(false)
                if (snapshot == null) clears++ else writes++
                records.value = AppResult.Success(snapshot)
                return AppResult.Success(true)
            }
        }
    val refresh = RefreshWorkCalendar(remote, cache, sessions, workspaces, clock, build)
    val observe = ObserveWorkCalendar(cache, sessions, workspaces, clock, build)

    suspend fun load(selection: WorkCalendarSelection = WorkCalendarSelection()) =
        refresh(workspace.id, selection)
}
