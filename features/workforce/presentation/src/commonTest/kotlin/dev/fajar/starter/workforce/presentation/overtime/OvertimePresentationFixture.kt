package dev.fajar.starter.workforce.presentation.overtime

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
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
import kotlinx.coroutines.sync.Mutex
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
    val access =
        (admitOvertime(workspace.id, workspace, session, build, clock.now()) as AppResult.Success)
            .value
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
    private val transport =
        object : OvertimeRepository, OvertimeRecordRepository {
            override suspend fun request(
                access: WorkforceAccess,
                id: String,
                historyAfter: Long?,
            ): AppResult<OvertimeDetails> {
                canonicalReads++
                canonicalSelections += id to historyAfter
                return canonicalCall(id, historyAfter)
            }

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
    val remote: OvertimeRepository
        get() = transport

    var canonicalReads = 0
    val canonicalSelections = mutableListOf<Pair<String, Long?>>()
    var canonicalCall: suspend (String, Long?) -> AppResult<OvertimeDetails> = { id, after ->
        detailsCall(selection.copy(requestId = id, historyAfter = after))
    }
    var bootstrapReads = 0
    var changesReads = 0
    val feedRequests = mutableListOf<Triple<Set<SyncCollection>, String?, Int>>()
    var bootstrapCall: suspend (String?) -> AppResult<SyncBootstrapPage> = {
        AppResult.Success(
            SyncBootstrapPage(
                setOf(SyncCollection.OVERTIME_REQUESTS),
                listOf(
                    SyncReference(SyncCollection.OVERTIME_REQUESTS, request.id, request.version)
                ),
                null,
                "cursor/0",
                clock.now(),
            )
        )
    }
    var changesCall: suspend (String) -> AppResult<SyncChangePage> = { cursor ->
        AppResult.Success(SyncChangePage(emptyList(), cursor, false, false, 5, clock.now()))
    }
    val feed =
        object : SyncFeedRepository {
            override suspend fun bootstrap(
                sessionId: String,
                companyId: String,
                collections: Set<SyncCollection>,
                cursor: String?,
                limit: Int,
            ): AppResult<SyncBootstrapPage> {
                bootstrapReads++
                feedRequests += Triple(collections, cursor, limit)
                return bootstrapCall(cursor)
            }

            override suspend fun changes(
                sessionId: String,
                companyId: String,
                collections: Set<SyncCollection>,
                cursor: String,
                limit: Int,
            ): AppResult<SyncChangePage> {
                changesReads++
                feedRequests += Triple(collections, cursor, limit)
                return changesCall(cursor)
            }
        }
    val replicas = OvertimeReplicaFixture()
    val execution = Mutex()
    val historyPages = mutableMapOf<Pair<String, Long?>, OvertimeHistoryPage>()
    val legacy =
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
        }
    val cache =
        object : OvertimeRequestReplicaRepository {
            override fun observeAdmission(access: WorkforceAccess) =
                replicas.observeAdmission(access)

            override suspend fun admission(access: WorkforceAccess) = replicas.admission(access)

            override suspend fun restrict(access: WorkforceAccess, failure: Failure) =
                replicas.restrict(access, failure)

            override suspend fun admit(access: WorkforceAccess, expectedRevision: String?) =
                replicas.admit(access, expectedRevision)

            override fun observeRequest(
                access: WorkforceAccess,
                selection: OvertimeRequestSelection,
            ) = flow {
                observers++
                try {
                    if (failNextDetailObservation) {
                        failNextDetailObservation = false
                        emit(overtimeFailure("fixture.storage", FailureKind.Storage))
                    } else
                        emitAll(
                            detailRecords.map { result ->
                                when (result) {
                                    is AppResult.Failed -> result
                                    is AppResult.Success -> {
                                        val saved = result.value
                                        val history =
                                            historyPages[
                                                selection.requestId to selection.historyAfter]
                                        AppResult.Success(
                                            if (selection.historyAfter == null) saved
                                            else if (saved != null && history != null)
                                                saved.copy(
                                                    value =
                                                        OvertimeRequestView(
                                                            selection,
                                                            saved.value.details.copy(
                                                                history = history.history
                                                            ),
                                                        )
                                                )
                                            else null
                                        )
                                    }
                                }
                            }
                        )
                } finally {
                    observers--
                }
            }

            override suspend fun requestCheckpoint(access: WorkforceAccess) =
                AppResult.Success((detailRecords.value as? AppResult.Success)?.value?.generation)

            override suspend fun replaceRequest(
                access: WorkforceAccess,
                requestId: String,
                expectedCheckpoint: String?,
                snapshot: OvertimeSnapshot<OvertimeRequestView>?,
                history: OvertimeHistoryPage?,
            ): AppResult<Boolean> {
                beforeWrite()
                if (snapshot == null && failCleanup)
                    return overtimeFailure("fixture.storage", FailureKind.Storage)
                if (
                    rejectWrite ||
                        expectedCheckpoint !=
                            (detailRecords.value as? AppResult.Success)?.value?.generation
                )
                    return AppResult.Success(false)
                if (snapshot != null) writes++
                if (history != null) historyPages[history.requestId to history.after] = history
                detailRecords.value = AppResult.Success(snapshot)
                return AppResult.Success(true)
            }

            override suspend fun pendingHistory(access: WorkforceAccess, limit: Int) =
                AppResult.Success(emptyList<OvertimeHistoryWork>())

            override suspend fun storeHistory(
                access: WorkforceAccess,
                work: OvertimeHistoryWork,
                page: OvertimeHistoryPage,
            ) = AppResult.Success(false)
        }
    val sync =
        SyncOvertime(
            feed,
            transport,
            workforce,
            replicas,
            sessions,
            workspaces,
            clock,
            build,
            execution,
        )
    val refreshRequest =
        LoadOvertimeRequest(workforce, remote, cache, sessions, workspaces, clock, build, execution)
    val observe = ObserveOvertime(replicas, legacy, sessions, workspaces, clock, build)
    val observeRequest = ObserveOvertimeRequest(cache, sessions, workspaces, clock, build)
}
