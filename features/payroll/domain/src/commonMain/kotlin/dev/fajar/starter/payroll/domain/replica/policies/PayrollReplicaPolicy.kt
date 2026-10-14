package dev.fajar.starter.payroll.domain.replica.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.entities.PayrollAccess
import dev.fajar.starter.payroll.domain.policies.*
import dev.fajar.starter.payroll.domain.replica.entities.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

fun initialPayrollCheckpoint(access: PayrollAccess, generation: String, now: Instant) =
    PayrollCheckpoint(
        access,
        generation,
        0,
        PayrollSyncPhase.BOOTSTRAP,
        null,
        false,
        null,
        now + PAYSLIP_FRESHNESS_PERIOD,
        null,
    )

internal fun advancePayrollCheckpoint(
    previous: PayrollCheckpoint,
    page: PayrollSyncPage,
    now: Instant,
): PayrollCheckpoint {
    val caughtUp =
        page.phase == PayrollSyncPhase.CHANGES && !page.hasMore && !page.pendingPublication
    return previous.copy(
        revision = previous.revision + 1,
        phase = page.phase,
        cursor = page.cursor,
        published = previous.published || caughtUp,
        refreshedAt = if (caughtUp) now else previous.refreshedAt,
        refreshAfter = if (caughtUp) now + PAYSLIP_FRESHNESS_PERIOD else previous.refreshAfter,
        pollAt = if (page.hasMore || caughtUp) null else now + page.pollAfterSeconds.seconds,
    )
}

/**
 * Present payroll entitlement is checked before observation; other grants do not change ownership.
 */
fun sameStoredPayrollScope(first: PayrollAccess, second: PayrollAccess): Boolean =
    first.accountId == second.accountId &&
        first.audience == second.audience &&
        first.companyId == second.companyId

fun validateStoredPayrollReplica(
    access: PayrollAccess,
    checkpoint: PayrollCheckpoint?,
): AppResult<Unit> =
    if (checkpoint != null && !sameStoredPayrollScope(access, checkpoint.access))
        payslipFailure("client.payslip_scope_changed", FailureKind.AccessDenied)
    else AppResult.Success(Unit)
