package dev.fajar.starter.payroll.presentation

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.*
import dev.fajar.starter.payroll.domain.replica.entities.*
import dev.fajar.starter.payroll.domain.replica.usecases.SyncPayroll
import dev.fajar.starter.payroll.domain.repositories.*
import dev.fajar.starter.payroll.domain.usecases.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

class PayrollFixture(instant: () -> Instant = { Instant.parse("2026-10-13T00:00:00Z") }) {
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
            setOf("payroll.self.read"),
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
        (admitPayroll(workspace.id, workspace, session, build, clock.now()) as AppResult.Success)
            .value
    val zero = DecimalAmount.parse("0")
    val summary =
        PayslipSummary(
            "44444444-4444-4444-4444-444444444444",
            "33333333-3333-3333-3333-333333333333",
            "E01",
            "Alex",
            YearMonth(2026, 9),
            LocalDate(2026, 9, 28),
            "IDR",
            DecimalAmount.parse("10500000"),
            DecimalAmount.parse("157500"),
            DecimalAmount.parse("10232500"),
            clock.now(),
        )
    val slip =
        Payslip(
            summary,
            "CO",
            "Company",
            PayslipBreakdown(
                listOf(
                    PayslipEarning(
                        PayslipEarningKind.BASIC,
                        "BASIC",
                        "Basic pay",
                        DecimalAmount.parse("10500000"),
                        true,
                    )
                ),
                emptyList(),
                zero,
                zero,
                zero,
                zero,
                zero,
                DecimalAmount.parse("110000"),
                zero,
                "pph21-ter-2026",
            ),
        )
    val payment = PayslipPayments(summary.id, 0, emptyList())
    val records =
        MutableStateFlow<AppResult<PayslipSnapshot<PayslipOverview>?>>(AppResult.Success(null))
    val detailRecords =
        MutableStateFlow<AppResult<PayslipSnapshot<PayslipView>?>>(AppResult.Success(null))
    val slips = mutableMapOf(summary.id to slip)
    val payments = mutableMapOf(summary.id to payment)
    var listReads = 0
    var detailReads = 0
    var paymentReads = 0
    var bootstrapReads = 0
    var changeReads = 0
    var legacyObservers = 0
    var detailCall: suspend (String) -> AppResult<Payslip> = {
        AppResult.Success(slips.getValue(it))
    }
    var paymentCall: suspend (String) -> AppResult<PayslipPayments> = {
        AppResult.Success(payments.getValue(it))
    }
    var bootstrapCall: suspend (String?, Int) -> AppResult<SyncBootstrapPage> = { cursor, limit ->
        val after = cursor?.removePrefix("bootstrap/")?.toInt() ?: 0
        val all = slips.keys.sorted().map { SyncReference(SyncCollection.PAYSLIPS, it, 0) }
        val page = all.drop(after).take(limit)
        val more = after + page.size < all.size
        AppResult.Success(
            SyncBootstrapPage(
                collections,
                page,
                if (more) "bootstrap/${after + page.size}" else null,
                if (more) null else "changes/0",
                clock.now(),
            )
        )
    }
    var changesCall: suspend (String) -> AppResult<SyncChangePage> = {
        AppResult.Success(changePage())
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
                check(collections == PayrollFixture.collections && limit == 8)
                bootstrapReads++
                return bootstrapCall(cursor, limit)
            }

            override suspend fun changes(
                sessionId: String,
                companyId: String,
                collections: Set<SyncCollection>,
                cursor: String,
                limit: Int,
            ): AppResult<SyncChangePage> {
                check(collections == PayrollFixture.collections && limit == 8)
                changeReads++
                return changesCall(cursor)
            }
        }
    val remote =
        object : PayslipRepository {
            override suspend fun list(
                access: PayrollAccess,
                selection: PayslipSelection,
            ): AppResult<PayslipPage> {
                listReads++
                error("Replica acquisition must not fetch the last selected list.")
            }

            override suspend fun details(access: PayrollAccess, id: String): AppResult<Payslip> {
                detailReads++
                return detailCall(id)
            }

            override suspend fun payments(
                access: PayrollAccess,
                id: String,
            ): AppResult<PayslipPayments> {
                paymentReads++
                return paymentCall(id)
            }
        }
    val cache =
        object : PayslipCacheRepository {
            override fun observe(access: PayrollAccess) = flow {
                legacyObservers++
                try {
                    emitAll(records)
                } finally {
                    legacyObservers--
                }
            }

            override fun observeDetails(access: PayrollAccess) = flow {
                legacyObservers++
                try {
                    emitAll(detailRecords)
                } finally {
                    legacyObservers--
                }
            }

            override suspend fun generation(access: PayrollAccess) =
                AppResult.Success((records.value as? AppResult.Success)?.value?.generation)

            override suspend fun detailsGeneration(access: PayrollAccess) =
                AppResult.Success((detailRecords.value as? AppResult.Success)?.value?.generation)

            override suspend fun replace(
                access: PayrollAccess,
                expectedGeneration: String?,
                snapshot: PayslipSnapshot<PayslipOverview>?,
            ): AppResult<Boolean> {
                records.value = AppResult.Success(snapshot)
                return AppResult.Success(true)
            }

            override suspend fun replaceDetails(
                access: PayrollAccess,
                expectedGeneration: String?,
                snapshot: PayslipSnapshot<PayslipView>?,
            ): AppResult<Boolean> {
                detailRecords.value = AppResult.Success(snapshot)
                return AppResult.Success(true)
            }
        }
    val replicas =
        PayrollReplicaFake(
            owns = { access ->
                (sessionState.value as? AppResult.Success)?.value?.let {
                    it.id == access.sessionId &&
                        it.user.id == access.accountId &&
                        it.binding.audience == access.audience
                } == true
            },
            clearLegacy = {
                records.value = AppResult.Success(null)
                detailRecords.value = AppResult.Success(null)
            },
        )
    val sync = SyncPayroll(feed, remote, replicas, sessions, workspaces, clock, build)
    val observe = ObservePayslips(replicas, cache, sessions, workspaces, clock, build)
    val observeDetail = ObservePayslip(replicas, cache, sessions, workspaces, clock, build)
    val observers
        get() = replicas.observers + legacyObservers

    suspend fun load() = sync(workspace.id)

    fun changePage(
        items: List<SyncChange> = emptyList(),
        cursor: String = "changes/next",
        hasMore: Boolean = false,
        pending: Boolean = false,
        poll: Int = 5,
    ) = SyncChangePage(items, cursor, hasMore, pending, poll, clock.now())

    fun changedPayment(version: Long = payment.version) =
        SyncChange(
            SyncReference(SyncCollection.PAYROLL_PAYMENTS, summary.id, version),
            SyncOperation.UPSERT,
        )

    fun snapshot() =
        (replicas.published.value as? AppResult.Success)?.value?.takeIf { it.checkpoint != null }

    companion object {
        val collections = setOf(SyncCollection.PAYSLIPS, SyncCollection.PAYROLL_PAYMENTS)
    }
}
