package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workforce.domain.WorkCalendarFixture
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.*
import dev.fajar.starter.workforce.domain.overtime.usecases.*
import kotlin.time.Instant
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.*

class OvertimeFixture(instant: () -> Instant = { Instant.parse("2026-10-12T01:00:00Z") }) {
    val base = WorkCalendarFixture(instant)
    val workspace = base.workspace.copy(permissions = setOf("overtime.self.manage"))

    init {
        base.workspaceState.value = AppResult.Success(workspace)
    }

    val access =
        (admitOvertime(workspace.id, workspace, base.session, base.build, base.clock.now())
                as AppResult.Success)
            .value
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
            base.employee.id,
            "E01",
            "Alex",
            base.session.user.id,
            base.session.user.id,
            base.clock.now(),
            base.directory.asOf,
            "Asia/Jakarta",
            WorkDay.Off(base.directory.asOf, CalendarOrigin.ROSTER, 0, null),
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
                        base.session.user.id,
                        base.clock.now(),
                        request.reason,
                    )
                )
            ),
            setOf(OvertimeAction.SUBMIT_ACTUAL, OvertimeAction.WITHDRAW),
        )
    val selection = OvertimeRequestSelection(request.id, base.employee.id)
    val records =
        MutableStateFlow<AppResult<OvertimeSnapshot<OvertimeOverview>?>>(AppResult.Success(null))
    val detailRecords =
        MutableStateFlow<AppResult<OvertimeSnapshot<OvertimeRequestView>?>>(AppResult.Success(null))
    var requestsCall: suspend () -> AppResult<OvertimePage<OvertimeSummary>> = {
        AppResult.Success(OvertimePage(listOf(request.toSummary())))
    }
    var detailsCall: suspend () -> AppResult<OvertimeDetails> = { AppResult.Success(details) }
    var beforeWrite: suspend () -> Unit = {}
    var rejectWrite = false
    var failCleanup = false
    var writes = 0
    var clears = 0
    var requestReads = 0
    var detailReads = 0
    var observers = 0
    var acquiredPeriod: CalendarPeriod? = null
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
                acquiredPeriod = period
                return requestsCall()
            }

            override suspend fun details(
                access: WorkforceAccess,
                selection: OvertimeRequestSelection,
            ): AppResult<OvertimeDetails> {
                detailReads++
                return detailsCall()
            }
        }
    val remote: OvertimeRepository
        get() = transport

    var canonicalReads = 0
    val canonicalSelections = mutableListOf<Pair<String, Long?>>()
    var canonicalCall: suspend (String, Long?) -> AppResult<OvertimeDetails> = { id, after ->
        detailsCall()
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
                base.clock.now(),
            )
        )
    }
    var changesCall: suspend (String) -> AppResult<SyncChangePage> = { cursor ->
        AppResult.Success(SyncChangePage(emptyList(), cursor, false, false, 5, base.clock.now()))
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
                    emitAll(records)
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
                    emitAll(
                        detailRecords.map { result ->
                            when (result) {
                                is AppResult.Failed -> result
                                is AppResult.Success -> {
                                    val saved = result.value
                                    val history =
                                        historyPages[selection.requestId to selection.historyAfter]
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
                if (snapshot == null) clears++ else writes++
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
            base.remote,
            replicas,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
            execution,
        )
    val refreshRequest =
        LoadOvertimeRequest(
            base.remote,
            remote,
            cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
            execution,
        )
    val observe =
        ObserveOvertime(replicas, legacy, base.sessions, base.workspaces, base.clock, base.build)
    val observeRequest =
        ObserveOvertimeRequest(cache, base.sessions, base.workspaces, base.clock, base.build)

    suspend fun load(detail: Boolean = false): AppResult<Unit> =
        if (detail) refreshRequest(workspace.id, selection)
        else
            when (val result = sync(workspace.id)) {
                is AppResult.Failed -> result
                is AppResult.Success -> AppResult.Success(Unit)
            }
}
