package dev.fajar.starter.expenses.domain

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.entities.*
import dev.fajar.starter.expenses.domain.replica.usecases.*
import dev.fajar.starter.expenses.domain.repositories.*
import dev.fajar.starter.expenses.domain.usecases.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.*

class ExpenseFixture(instant: () -> Instant = { Instant.parse("2026-10-13T00:00:00Z") }) {
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
            setOf("expenses.self.manage"),
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
    var sessionCall: suspend () -> AppResult<Session?> = { sessionState.value }
    var policyCall: suspend () -> AppResult<CompanyPolicy> = {
        AppResult.Success(
            policy.copy(serverTime = clock.now(), validUntil = clock.now() + 60.seconds)
        )
    }
    var policyReads = 0
    val sessions =
        object : SessionRepository {
            override val persistent = false

            override fun observe() = sessionState

            override suspend fun current() = sessionCall()

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
    val access =
        (admitExpenses(workspace.id, workspace, session, build, clock.now()) as AppResult.Success)
            .value
    val employee =
        ExpenseEmployment(
            "33333333-3333-3333-3333-333333333333",
            "E01",
            ExpenseEmploymentStatus.ACTIVE,
            0,
        )
    val directory =
        ExpenseDirectory(
            access.accountId,
            access.companyId,
            clock.now().toLocalDateTime(TimeZone.of(access.timezone)).date,
            access.timezone,
            listOf(employee),
            null,
        )
    val amount = DecimalAmount.parse("120000.25")
    val line =
        ExpenseLine(
            "55555555-5555-5555-5555-555555555555",
            "66666666-6666-6666-6666-666666666666",
            directory.asOf,
            amount,
            "Client transport",
            null,
            emptyList(),
        )
    val draft =
        ExpenseDraft(
            0,
            employee.employeeNumber,
            "Alex",
            "Client visit",
            "Transport reimbursement",
            amount,
            "IDR",
            listOf(line),
            session.user.id,
            "Created",
            clock.now(),
        )
    val claim =
        ExpenseClaim(
            "44444444-4444-4444-4444-444444444444",
            employee.id,
            session.user.id,
            clock.now(),
            0,
            ExpenseStatus.DRAFT,
            draft,
            0,
            null,
        )
    val summary =
        ExpenseSummary(
            claim.id,
            employee.id,
            employee.employeeNumber,
            "Alex",
            draft.title,
            amount,
            "IDR",
            claim.createdAt,
            claim.status,
            claim.version,
        )
    val change =
        ExpenseChange(
            0,
            0,
            ExpenseStatus.DRAFT,
            ExpenseChangeKind.DRAFT_SAVED,
            session.user.id,
            "Created",
            clock.now(),
            null,
        )
    val history = ExpensePage(listOf(change))
    val records =
        MutableStateFlow<AppResult<ExpenseSnapshot<ExpenseOverview>?>>(AppResult.Success(null))
    val detailRecords =
        MutableStateFlow<AppResult<ExpenseSnapshot<ExpenseView>?>>(AppResult.Success(null))
    var directoryCall: suspend (String?) -> AppResult<ExpenseDirectory> = {
        AppResult.Success(directory)
    }
    var listCall: suspend (ExpenseSelection) -> AppResult<ExpensePage<ExpenseSummary>> = {
        AppResult.Success(ExpensePage(listOf(summary)))
    }
    var claimCall: suspend () -> AppResult<ExpenseClaim> = { AppResult.Success(claim) }
    var historyCall: suspend (Long?) -> AppResult<ExpensePage<ExpenseChange>> = {
        AppResult.Success(history)
    }
    var submissionCall: suspend (String) -> AppResult<ExpenseSubmission> = {
        error("No submission expected")
    }
    var paymentCall: suspend () -> AppResult<ExpenseClaimPayments> = {
        AppResult.Success(ExpenseClaimPayments(claim.id, 0, emptyList()))
    }
    var beforeWrite: suspend (Boolean) -> Unit
        get() = replicas.beforeWrite
        set(value) {
            replicas.beforeWrite = value
        }

    var failCleanup: Boolean
        get() = replicas.failCleanup
        set(value) {
            replicas.failCleanup = value
        }

    private var legacyWrites = 0
    val writes
        get() = legacyWrites + replicas.writes

    private var legacyClears = 0
    val clears
        get() = legacyClears + replicas.clears

    private var legacyObservers = 0
    val observers
        get() = legacyObservers + replicas.observers

    var directoryReads = 0
    var listReads = 0
    var claimReads = 0
    var historyReads = 0
    var submissionReads = 0
    var paymentReads = 0
    var lastDirectory = directory
    var recordReads = 0
    var recordCall: suspend (String, Long?) -> AppResult<ExpenseClaimRecord> = { _, after ->
        when (val result = claimCall()) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                when (val history = historyCall(after)) {
                    is AppResult.Failed -> history
                    is AppResult.Success -> {
                        val claim = result.value
                        val submission = claim.latestSubmissionId?.let { submissionCall(it) }
                        if (submission is AppResult.Failed) submission
                        else
                            AppResult.Success(
                                ExpenseClaimRecord(
                                    claim,
                                    (submission as? AppResult.Success)?.value,
                                    history.value,
                                    after,
                                )
                            )
                    }
                }
        }
    }
    val remote =
        object : ExpenseRepository {
            override suspend fun record(
                access: ExpenseAccess,
                id: String,
                historyAfter: Long?,
            ): AppResult<ExpenseClaimRecord> {
                recordReads++
                claimReads++
                return recordCall(id, historyAfter)
            }

            override suspend fun directory(
                access: ExpenseAccess,
                after: String?,
            ): AppResult<ExpenseDirectory> {
                directoryReads++
                return directoryCall(after).also {
                    if (it is AppResult.Success) lastDirectory = it.value
                }
            }

            override suspend fun list(
                access: ExpenseAccess,
                selection: ExpenseSelection,
            ): AppResult<ExpensePage<ExpenseSummary>> {
                listReads++
                return listCall(selection)
            }

            override suspend fun claim(access: ExpenseAccess, id: String): AppResult<ExpenseClaim> {
                claimReads++
                return claimCall()
            }

            override suspend fun history(
                access: ExpenseAccess,
                id: String,
                after: Long?,
            ): AppResult<ExpensePage<ExpenseChange>> {
                historyReads++
                return historyCall(after)
            }

            override suspend fun submission(
                access: ExpenseAccess,
                id: String,
            ): AppResult<ExpenseSubmission> {
                submissionReads++
                return submissionCall(id)
            }

            override suspend fun payments(
                access: ExpenseAccess,
                id: String,
            ): AppResult<ExpenseClaimPayments> {
                paymentReads++
                return paymentCall()
            }
        }
    val cache =
        object : ExpenseCacheRepository {
            override fun observe(access: ExpenseAccess) = flow {
                legacyObservers++
                try {
                    emitAll(records)
                } finally {
                    legacyObservers--
                }
            }

            override fun observeDetails(access: ExpenseAccess) = flow {
                legacyObservers++
                try {
                    emitAll(detailRecords)
                } finally {
                    legacyObservers--
                }
            }

            override suspend fun generation(access: ExpenseAccess) =
                AppResult.Success((records.value as? AppResult.Success)?.value?.generation)

            override suspend fun detailsGeneration(access: ExpenseAccess) =
                AppResult.Success((detailRecords.value as? AppResult.Success)?.value?.generation)

            override suspend fun replace(
                access: ExpenseAccess,
                expectedGeneration: String?,
                snapshot: ExpenseSnapshot<ExpenseOverview>?,
            ): AppResult<Boolean> {
                beforeWrite(snapshot == null)
                if (snapshot == null && failCleanup)
                    return expenseFailure("fixture.storage", FailureKind.Storage)
                if ((records.value as? AppResult.Success)?.value?.generation != expectedGeneration)
                    return AppResult.Success(false)
                if (snapshot == null) legacyClears++ else legacyWrites++
                records.value = AppResult.Success(snapshot)
                return AppResult.Success(true)
            }

            override suspend fun replaceDetails(
                access: ExpenseAccess,
                expectedGeneration: String?,
                snapshot: ExpenseSnapshot<ExpenseView>?,
            ): AppResult<Boolean> {
                beforeWrite(snapshot == null)
                if (snapshot == null && failCleanup)
                    return expenseFailure("fixture.storage", FailureKind.Storage)
                if (
                    (detailRecords.value as? AppResult.Success)?.value?.generation !=
                        expectedGeneration
                )
                    return AppResult.Success(false)
                if (snapshot == null) legacyClears++ else legacyWrites++
                detailRecords.value = AppResult.Success(snapshot)
                return AppResult.Success(true)
            }
        }
    val replicas =
        ExpenseReplicaFake(
            owns = { access ->
                val owner = (sessionState.value as? AppResult.Success)?.value
                val active = (workspaceState.value as? AppResult.Success)?.value
                owner?.id == access.sessionId &&
                    owner.user.id == access.accountId &&
                    active?.company?.id == access.companyId
            },
            clearLegacy = {
                records.value = AppResult.Success(null)
                detailRecords.value = AppResult.Success(null)
            },
        )
    val requestedCollections = mutableListOf<Set<SyncCollection>>()
    val feedLimits = mutableListOf<Int>()
    var bootstrapReads = 0
    var changeReads = 0
    var bootstrapCall: suspend (Set<SyncCollection>, String?) -> AppResult<SyncBootstrapPage> =
        { collections, _ ->
            AppResult.Success(
                SyncBootstrapPage(
                    collections,
                    if (lastDirectory.items.isEmpty()) emptyList()
                    else
                        listOf(
                            SyncReference(SyncCollection.EXPENSE_CLAIMS, claim.id, claim.version)
                        ),
                    null,
                    "changes",
                    clock.now(),
                )
            )
        }
    var changesCall: suspend (String) -> AppResult<SyncChangePage> = {
        AppResult.Success(
            SyncChangePage(emptyList(), "changes-$changeReads", false, false, 5, clock.now())
        )
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
                requestedCollections += collections
                feedLimits += limit
                return bootstrapCall(collections, cursor)
            }

            override suspend fun changes(
                sessionId: String,
                companyId: String,
                collections: Set<SyncCollection>,
                cursor: String,
                limit: Int,
            ): AppResult<SyncChangePage> {
                changeReads++
                requestedCollections += collections
                feedLimits += limit
                return changesCall(cursor)
            }
        }
    val acquisition = Mutex()
    val sync = SyncExpenses(feed, remote, replicas, sessions, workspaces, clock, build, acquisition)
    val refreshDetail =
        LoadExpenseClaim(remote, replicas, sessions, workspaces, clock, build, acquisition)
    val observe = ObserveExpenses(replicas, cache, sessions, workspaces, clock, build)
    val observeDetail = ObserveExpenseClaim(replicas, cache, sessions, workspaces, clock, build)

    suspend fun load(detail: Boolean = false): AppResult<Unit> =
        if (detail) refreshDetail(workspace.id, summary.id)
        else
            when (val result = sync(workspace.id)) {
                is AppResult.Failed -> result
                is AppResult.Success -> AppResult.Success(Unit)
            }

    suspend fun snapshot(detail: Boolean): ExpenseSnapshot<*>? =
        if (detail) (observeDetail(workspace.id, claim.id).first() as? AppResult.Success)?.value
        else (observe(workspace.id).first() as? AppResult.Success)?.value

    fun seedDetails(snapshot: ExpenseSnapshot<ExpenseView>) = replicas.seed(snapshot)
}
