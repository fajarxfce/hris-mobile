package dev.fajar.starter.payroll.presentation

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.*
import dev.fajar.starter.payroll.domain.repositories.*
import dev.fajar.starter.payroll.domain.usecases.*
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
    var listCall: suspend (PayslipSelection) -> AppResult<PayslipPage> = {
        AppResult.Success(PayslipPage(listOf(summary)))
    }
    var detailCall: suspend () -> AppResult<Payslip> = { AppResult.Success(slip) }
    var paymentCall: suspend () -> AppResult<PayslipPayments> = { AppResult.Success(payment) }
    var beforeWrite: suspend (Boolean) -> Unit = {}
    var failCleanup = false
    var failNextObservation = false
    var failNextDetailObservation = false
    var writes = 0
    var clears = 0
    var observers = 0
    var listReads = 0
    var detailReads = 0
    var paymentReads = 0
    val remote =
        object : PayslipRepository {
            override suspend fun list(
                access: PayrollAccess,
                selection: PayslipSelection,
            ): AppResult<PayslipPage> {
                listReads++
                return listCall(selection)
            }

            override suspend fun details(access: PayrollAccess, id: String): AppResult<Payslip> {
                detailReads++
                return detailCall()
            }

            override suspend fun payments(
                access: PayrollAccess,
                id: String,
            ): AppResult<PayslipPayments> {
                paymentReads++
                return paymentCall()
            }
        }
    val cache =
        object : PayslipCacheRepository {
            override fun observe(access: PayrollAccess) = flow {
                observers++
                try {
                    if (failNextObservation) {
                        failNextObservation = false
                        emit(payslipFailure("fixture.storage", FailureKind.Storage))
                    } else emitAll(records)
                } finally {
                    observers--
                }
            }

            override fun observeDetails(access: PayrollAccess) = flow {
                observers++
                try {
                    if (failNextDetailObservation) {
                        failNextDetailObservation = false
                        emit(payslipFailure("fixture.storage", FailureKind.Storage))
                    } else emitAll(detailRecords)
                } finally {
                    observers--
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
                beforeWrite(snapshot == null)
                if (snapshot == null && failCleanup)
                    return payslipFailure("fixture.storage", FailureKind.Storage)
                if ((records.value as? AppResult.Success)?.value?.generation != expectedGeneration)
                    return AppResult.Success(false)
                if (snapshot == null) clears++ else writes++
                records.value = AppResult.Success(snapshot)
                return AppResult.Success(true)
            }

            override suspend fun replaceDetails(
                access: PayrollAccess,
                expectedGeneration: String?,
                snapshot: PayslipSnapshot<PayslipView>?,
            ): AppResult<Boolean> {
                beforeWrite(snapshot == null)
                if (snapshot == null && failCleanup)
                    return payslipFailure("fixture.storage", FailureKind.Storage)
                if (
                    (detailRecords.value as? AppResult.Success)?.value?.generation !=
                        expectedGeneration
                )
                    return AppResult.Success(false)
                if (snapshot == null) clears++ else writes++
                detailRecords.value = AppResult.Success(snapshot)
                return AppResult.Success(true)
            }
        }
    val refresh = RefreshPayslips(remote, cache, sessions, workspaces, clock, build)
    val refreshDetail = RefreshPayslip(remote, cache, sessions, workspaces, clock, build)
    val observe = ObservePayslips(cache, sessions, workspaces, clock, build)
    val observeDetail = ObservePayslip(cache, sessions, workspaces, clock, build)

    suspend fun load(detail: Boolean = false): AppResult<Unit> =
        if (detail) refreshDetail(workspace.id, summary.id)
        else refresh(workspace.id, PayslipSelection())

    fun snapshot(detail: Boolean) =
        if (detail) (detailRecords.value as? AppResult.Success)?.value
        else (records.value as? AppResult.Success)?.value
}
