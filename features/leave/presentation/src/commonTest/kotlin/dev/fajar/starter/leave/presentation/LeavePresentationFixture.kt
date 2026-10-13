package dev.fajar.starter.leave.presentation

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.repositories.*
import dev.fajar.starter.leave.domain.usecases.*
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

class LeavePresentationFixture(instant: () -> Instant = { Instant.parse("2026-10-12T01:00:00Z") }) {
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
            setOf("leave.self.manage"),
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
    val records = MutableStateFlow<AppResult<LeaveOverview?>>(AppResult.Success(null))
    val employee =
        LeaveEmployment(
            "33333333-3333-3333-3333-333333333333",
            "E01",
            LeaveEmploymentStatus.ACTIVE,
            0,
        )
    val directory =
        LeaveDirectory(
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
    var directoryCall: suspend () -> AppResult<LeaveDirectory> = { AppResult.Success(directory) }
    val balance =
        LeaveBalance(
            "44444444-4444-4444-4444-444444444444",
            "55555555-5555-5555-5555-555555555555",
            "ANNUAL",
            "Annual leave",
            2026,
            LeaveDays(21),
            LeaveDays(2),
            LeaveDays(1),
            false,
            3,
        )
    val request =
        LeaveRequestSummary(
            "66666666-6666-6666-6666-666666666666",
            employee.id,
            "ANNUAL",
            "Annual leave",
            LocalDate(2026, 10, 15),
            LocalDate(2026, 10, 16),
            LeaveDays(3),
            LeaveStatus.PENDING,
            clock.now(),
            0,
        )
    var balancesCall: suspend (Int) -> AppResult<LeavePage<LeaveBalance>> = { year ->
        AppResult.Success(LeavePage(listOf(balance.copy(year = year))))
    }
    var requestsCall: suspend (LeaveStatus?) -> AppResult<LeavePage<LeaveRequestSummary>> =
        { status ->
            AppResult.Success(
                LeavePage(listOf(request.copy(status = status ?: LeaveStatus.PENDING)))
            )
        }
    var policyReads = 0
    var directoryReads = 0
    var balanceReads = 0
    var requestReads = 0
    var legacyWrites = 0
    val writes
        get() = legacyWrites + replica.writes

    var legacyClears = 0
    val clears
        get() = legacyClears + replica.clears

    var legacyObservers = 0
    val observers
        get() = legacyObservers + replica.directoryObservers

    var rejectWrite: Boolean
        get() = replica.rejectWrite
        set(value) {
            replica.rejectWrite = value
        }

    var cacheReadGate: CompletableDeferred<Unit>?
        get() = replica.readGate
        set(value) {
            replica.readGate = value
        }

    var failNextObservation: Boolean
        get() = replica.failNextObservation
        set(value) {
            replica.failNextObservation = value
        }

    val years = mutableListOf<Int>()
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
        object : LeaveRepository {
            override suspend fun details(
                access: LeaveAccess,
                selection: LeaveRequestSelection,
            ): AppResult<LeaveRequestDetails> =
                error("Request details are not used by the overview fixture")

            override suspend fun selfEmployments(
                access: LeaveAccess,
                after: String?,
            ): AppResult<LeaveDirectory> {
                directoryReads++
                return directoryCall()
            }

            override suspend fun balances(
                access: LeaveAccess,
                employeeId: String,
                year: Int,
                after: String?,
            ): AppResult<LeavePage<LeaveBalance>> {
                balanceReads++
                years += year
                return balancesCall(year)
            }

            override suspend fun requests(
                access: LeaveAccess,
                employeeId: String,
                status: LeaveStatus?,
                after: String?,
            ): AppResult<LeavePage<LeaveRequestSummary>> {
                requestReads++
                return requestsCall(status)
            }
        }
    val cache =
        object : LeaveCacheRepository {
            override fun observe(access: LeaveAccess): Flow<AppResult<LeaveOverview?>> = flow {
                legacyObservers++
                try {
                    cacheReadGate?.await()
                    if (failNextObservation) {
                        failNextObservation = false
                        emit(leaveFailure("client.storage", FailureKind.Storage))
                        return@flow
                    }
                    emitAll(records)
                } finally {
                    legacyObservers--
                }
            }

            override suspend fun generation(access: LeaveAccess) =
                AppResult.Success((records.value as? AppResult.Success)?.value?.generation)

            override suspend fun replace(
                access: LeaveAccess,
                expectedGeneration: String?,
                snapshot: LeaveOverview?,
            ): AppResult<Boolean> {
                val current = (records.value as? AppResult.Success)?.value
                if (rejectWrite || expectedGeneration != current?.generation)
                    return AppResult.Success(false)
                if (snapshot == null) legacyClears++ else legacyWrites++
                records.value = AppResult.Success(snapshot)
                return AppResult.Success(true)
            }
        }
    val replica = LeaveReplicaFixture()
    val synchronization = LeaveSyncFixture(this)
    val refresh = synchronization.sync
    val observe = ObserveLeave(replica, cache, sessions, workspaces, clock, build)

    suspend fun load(selection: LeaveSelection = LeaveSelection()): AppResult<Unit> {
        val access =
            (admitLeave(workspace.id, workspace, session, build, clock.now()) as AppResult.Success)
                .value
        records.value =
            AppResult.Success(
                LeaveOverview(
                    access,
                    "legacy",
                    directory,
                    selection.copy(
                        employeeId = selection.employeeId ?: employee.id,
                        year = selection.year ?: 2026,
                    ),
                    LeavePage(listOf(balance.copy(year = selection.year ?: 2026))),
                    LeavePage(listOf(request.copy(status = selection.status ?: request.status))),
                    clock.now(),
                    clock.now() + 1.hours,
                )
            )
        return AppResult.Success(Unit)
    }
}
