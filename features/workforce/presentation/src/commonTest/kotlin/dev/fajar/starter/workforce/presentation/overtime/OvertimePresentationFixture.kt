package dev.fajar.starter.workforce.presentation.overtime

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.*
import dev.fajar.starter.workforce.domain.overtime.usecases.*
import dev.fajar.starter.workforce.domain.repositories.WorkforceRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

class OvertimePresentationFixture(
    instant: () -> Instant = { Instant.parse("2026-10-12T01:00:00Z") }
) {
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
            setOf("overtime.self.manage"),
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
    val interval =
        OvertimeInterval(
            Instant.parse("2026-10-12T10:00:00Z"),
            Instant.parse("2026-10-12T12:00:00Z"),
            0,
            120,
        )
    val request =
        OvertimeRequest(
            "66666666-6666-6666-6666-666666666666",
            employee.id,
            employee.employeeNumber,
            "Alex",
            session.user.id,
            session.user.id,
            clock.now(),
            directory.asOf,
            directory.timezone,
            WorkDay.Off(directory.asOf, CalendarOrigin.ROSTER, 0, null),
            interval,
            "Inventory count",
            OvertimeStatus.PLANNED,
            null,
            null,
            null,
            null,
            0,
            null,
            0,
        )
    val details =
        OvertimeDetails(
            request,
            null,
            OvertimePage(
                listOf(
                    OvertimeChange(
                        0,
                        OvertimeChangeKind.PLANNED,
                        OvertimeStatus.PLANNED,
                        null,
                        0,
                        null,
                        session.user.id,
                        clock.now(),
                        request.reason,
                    )
                )
            ),
            setOf(OvertimeAction.SUBMIT_ACTUAL, OvertimeAction.WITHDRAW),
        )
    val selection = OvertimeRequestSelection(request.id, employee.id)
    val records =
        MutableStateFlow<AppResult<OvertimeSnapshot<OvertimeOverview>?>>(AppResult.Success(null))
    val detailRecords =
        MutableStateFlow<AppResult<OvertimeSnapshot<OvertimeRequestView>?>>(AppResult.Success(null))
    var directoryCall: suspend () -> AppResult<WorkforceDirectory> = {
        AppResult.Success(directory)
    }
    var requestsCall:
        suspend (CalendarPeriod, OvertimeStatus?) -> AppResult<OvertimePage<OvertimeSummary>> =
        { period, status ->
            AppResult.Success(
                OvertimePage(
                    listOf(
                        request
                            .toSummary()
                            .copy(
                                workDate =
                                    if (request.workDate in period.from..period.until)
                                        request.workDate
                                    else period.from,
                                status = status ?: OvertimeStatus.PLANNED,
                            )
                    )
                )
            )
        }
    var detailsCall: suspend (OvertimeRequestSelection) -> AppResult<OvertimeDetails> = {
        AppResult.Success(details)
    }
    var beforeWrite: suspend () -> Unit = {}
    var failCleanup = false
    var rejectWrite = false
    var failNextObservation = false
    var failNextDetailObservation = false
    var cacheReadGate: CompletableDeferred<Unit>? = null
    var directoryReads = 0
    var requestReads = 0
    var detailReads = 0
    var writes = 0
    var observers = 0
    val periods = mutableListOf<CalendarPeriod>()
    val detailSelections = mutableListOf<OvertimeRequestSelection>()
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

            override suspend fun clientPolicy(sessionId: String, companyId: String) =
                AppResult.Success(
                    policy.copy(serverTime = clock.now(), validUntil = clock.now() + 60.seconds)
                )
        }
    val workforce =
        object : WorkforceRepository {
            override suspend fun selfEmployments(
                access: WorkforceAccess,
                after: String?,
            ): AppResult<WorkforceDirectory> {
                directoryReads++
                return directoryCall()
            }

            override suspend fun calendar(
                access: WorkforceAccess,
                employeeId: String,
                period: CalendarPeriod,
            ): AppResult<WorkCalendar> = error("Overtime reads do not acquire the live calendar")
        }
    val remote =
        object : OvertimeRepository {
            override suspend fun requests(
                access: WorkforceAccess,
                employeeId: String,
                period: CalendarPeriod,
                status: OvertimeStatus?,
                after: String?,
            ): AppResult<OvertimePage<OvertimeSummary>> {
                requestReads++
                periods += period
                return requestsCall(period, status)
            }

            override suspend fun details(
                access: WorkforceAccess,
                selection: OvertimeRequestSelection,
            ): AppResult<OvertimeDetails> {
                detailReads++
                detailSelections += selection
                return detailsCall(selection)
            }
        }
    val cache =
        object : OvertimeCacheRepository {
            override fun observe(access: WorkforceAccess) = flow {
                observers++
                try {
                    cacheReadGate?.await()
                    if (failNextObservation) {
                        failNextObservation = false
                        emit(overtimeFailure("fixture.storage", FailureKind.Storage))
                    } else emitAll(records)
                } finally {
                    observers--
                }
            }

            override fun observeRequest(access: WorkforceAccess) = flow {
                observers++
                try {
                    if (failNextDetailObservation) {
                        failNextDetailObservation = false
                        emit(overtimeFailure("fixture.storage", FailureKind.Storage))
                    } else emitAll(detailRecords)
                } finally {
                    observers--
                }
            }

            override suspend fun generation(access: WorkforceAccess) =
                AppResult.Success((records.value as? AppResult.Success)?.value?.generation)

            override suspend fun requestGeneration(access: WorkforceAccess) =
                AppResult.Success((detailRecords.value as? AppResult.Success)?.value?.generation)

            override suspend fun replace(
                access: WorkforceAccess,
                expectedGeneration: String?,
                snapshot: OvertimeSnapshot<OvertimeOverview>?,
            ): AppResult<Boolean> {
                beforeWrite()
                if (snapshot == null && failCleanup)
                    return overtimeFailure("fixture.storage", FailureKind.Storage)
                if (
                    rejectWrite ||
                        expectedGeneration !=
                            (records.value as? AppResult.Success)?.value?.generation
                )
                    return AppResult.Success(false)
                if (snapshot != null) writes++
                records.value = AppResult.Success(snapshot)
                return AppResult.Success(true)
            }

            override suspend fun replaceRequest(
                access: WorkforceAccess,
                expectedGeneration: String?,
                snapshot: OvertimeSnapshot<OvertimeRequestView>?,
            ): AppResult<Boolean> {
                beforeWrite()
                if (snapshot == null && failCleanup)
                    return overtimeFailure("fixture.storage", FailureKind.Storage)
                if (
                    rejectWrite ||
                        expectedGeneration !=
                            (detailRecords.value as? AppResult.Success)?.value?.generation
                )
                    return AppResult.Success(false)
                if (snapshot != null) writes++
                detailRecords.value = AppResult.Success(snapshot)
                return AppResult.Success(true)
            }
        }
    val refresh = RefreshOvertime(workforce, remote, cache, sessions, workspaces, clock, build)
    val refreshRequest =
        RefreshOvertimeRequest(workforce, remote, cache, sessions, workspaces, clock, build)
    val observe = ObserveOvertime(cache, sessions, workspaces, clock, build)
    val observeRequest = ObserveOvertimeRequest(cache, sessions, workspaces, clock, build)
}
