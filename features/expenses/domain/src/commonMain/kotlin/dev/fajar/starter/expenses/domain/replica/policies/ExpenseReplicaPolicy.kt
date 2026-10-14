package dev.fajar.starter.expenses.domain.replica.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.entities.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

fun initialExpenseCheckpoint(access: ExpenseAccess, generation: String, now: Instant) =
    ExpenseCheckpoint(
        access,
        generation,
        0,
        ExpenseSyncPhase.BOOTSTRAP,
        null,
        false,
        null,
        now + EXPENSE_FRESHNESS_PERIOD,
        null,
    )

internal fun advanceExpenseCheckpoint(
    previous: ExpenseCheckpoint,
    page: ExpenseSyncPage,
    now: Instant,
): ExpenseCheckpoint {
    val caughtUp =
        page.phase == ExpenseSyncPhase.CHANGES && !page.hasMore && !page.pendingPublication
    return previous.copy(
        revision = previous.revision + 1,
        phase = page.phase,
        cursor = page.cursor,
        published = previous.published || caughtUp,
        refreshedAt = if (caughtUp) now else previous.refreshedAt,
        refreshAfter = if (caughtUp) now + EXPENSE_FRESHNESS_PERIOD else previous.refreshAfter,
        pollAt = if (page.hasMore || caughtUp) null else now + page.pollAfterSeconds.seconds,
    )
}

fun validateStoredExpenseReplica(
    access: ExpenseAccess,
    checkpoint: ExpenseCheckpoint?,
): AppResult<Unit> =
    if (checkpoint != null && !sameStoredExpenseScope(access, checkpoint.access))
        expenseFailure("client.expense_scope_changed", FailureKind.AccessDenied)
    else AppResult.Success(Unit)
