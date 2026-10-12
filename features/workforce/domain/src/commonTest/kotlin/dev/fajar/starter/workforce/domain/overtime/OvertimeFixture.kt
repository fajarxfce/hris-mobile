package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.WorkCalendarFixture
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.*
import dev.fajar.starter.workforce.domain.overtime.usecases.*
import kotlin.time.Instant
import kotlinx.coroutines.flow.*
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
    val cache =
        object : OvertimeCacheRepository {
            override fun observe(access: WorkforceAccess) = flow {
                observers++
                try {
                    emitAll(records)
                } finally {
                    observers--
                }
            }

            override fun observeRequest(access: WorkforceAccess) = flow {
                observers++
                try {
                    emitAll(detailRecords)
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
                if (snapshot == null) clears++ else writes++
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
                if (snapshot == null) clears++ else writes++
                detailRecords.value = AppResult.Success(snapshot)
                return AppResult.Success(true)
            }
        }
    val refresh =
        RefreshOvertime(
            base.remote,
            remote,
            cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val refreshRequest =
        RefreshOvertimeRequest(
            base.remote,
            remote,
            cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val observe = ObserveOvertime(cache, base.sessions, base.workspaces, base.clock, base.build)
    val observeRequest =
        ObserveOvertimeRequest(cache, base.sessions, base.workspaces, base.clock, base.build)

    suspend fun load(detail: Boolean = false): AppResult<Unit> =
        if (detail) refreshRequest(workspace.id, selection)
        else refresh(workspace.id, OvertimeSelection())
}
