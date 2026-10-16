package dev.fajar.starter.expenses.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.entities.ExpenseCheckpoint
import dev.fajar.starter.expenses.domain.replica.policies.validateStoredExpenseReplica
import dev.fajar.starter.expenses.domain.replica.repositories.ExpenseReplicaRepository
import dev.fajar.starter.expenses.domain.repositories.ExpenseCacheRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.CompanyDataStatus
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

class ObserveExpenses(
    private val replicas: ExpenseReplicaRepository,
    private val cache: ExpenseCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) : ObserveCompanyData {
    override val dataSet = CompanyDataSet.Expenses

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        selection: ExpenseSelection = ExpenseSelection(),
    ): Flow<AppResult<ExpenseSnapshot<ExpenseOverview>?>> {
        val valid = validateExpenseSelection(selection)
        if (valid is AppResult.Failed) return flowOf(valid)
        return observeAdmitted(workspaceId) { access -> observeReplica(access, selection) }
    }

    override fun observeStatus(workspaceId: String): Flow<AppResult<CompanyDataStatus>> =
        observeAdmitted(workspaceId) { access ->
                replicas.observePublishedCheckpoint(access).map { result ->
                    when (result) {
                        is AppResult.Failed -> result
                        is AppResult.Success -> {
                            val checkpoint = result.value
                            when (
                                val ownership = validateStoredExpenseReplica(access, checkpoint)
                            ) {
                                is AppResult.Failed -> ownership
                                is AppResult.Success ->
                                    AppResult.Success(CompanyDataStatus(checkpoint?.refreshedAt))
                            }
                        }
                    }
                }
            }
            .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> observeAdmitted(
        workspaceId: String,
        read: (ExpenseAccess) -> Flow<AppResult<T>>,
    ): Flow<AppResult<T>> =
        sessions
            .observe()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success -> {
                        val session = result.value
                        if (session == null)
                            flowOf(expenseFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { current ->
                                when (current) {
                                    is AppResult.Failed -> current
                                    is AppResult.Success ->
                                        admitExpenses(
                                            workspaceId,
                                            current.value,
                                            session,
                                            build,
                                            clock.now(),
                                        )
                                }
                            }
                    }
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { admitted ->
                when (admitted) {
                    is AppResult.Failed -> flowOf(admitted)
                    is AppResult.Success ->
                        replicas.observeAdmission(admitted.value).flatMapLatest { admission ->
                            when (admission) {
                                is AppResult.Failed -> flowOf(admission)
                                is AppResult.Success -> {
                                    val denial = admission.value.denial
                                    if (denial != null) flowOf(AppResult.Failed(denial))
                                    else read(admitted.value)
                                }
                            }
                        }
                }
            }
            .buffer(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeReplica(
        access: ExpenseAccess,
        requested: ExpenseSelection,
    ): Flow<AppResult<ExpenseSnapshot<ExpenseOverview>?>> =
        replicas.observeDirectory(access).flatMapLatest { loaded ->
            when (loaded) {
                is AppResult.Failed -> flowOf(loaded)
                is AppResult.Success -> {
                    val checkpoint = loaded.value.checkpoint
                    val directory = loaded.value.directory
                    if (checkpoint?.refreshedAt == null || directory == null)
                        observeLegacy(access, requested)
                    else
                        when (val owner = validateStoredExpenseReplica(access, checkpoint)) {
                            is AppResult.Failed -> flowOf(owner)
                            is AppResult.Success ->
                                observeSelectedPage(access, checkpoint, directory, requested)
                        }
                }
            }
        }

    /** Normalizes a local selection against the published own-employment directory. */
    private fun observeSelectedPage(
        access: ExpenseAccess,
        checkpoint: ExpenseCheckpoint,
        directory: ExpenseDirectory,
        requested: ExpenseSelection,
    ): Flow<AppResult<ExpenseSnapshot<ExpenseOverview>?>> {
        val remaining =
            directory.items.filter {
                requested.employmentAfter == null || it.employeeNumber > requested.employmentAfter
            }
        val employments = remaining.take(EXPENSE_PAGE_SIZE)
        val employee =
            employments.firstOrNull { it.id == requested.employeeId } ?: employments.firstOrNull()
        val now = clock.now().toLocalDateTime(TimeZone.of(directory.timezone))
        val selection =
            requested.copy(
                employeeId = employee?.id,
                month = requested.month ?: YearMonth(now.year, now.month),
                after =
                    requested.after?.takeIf {
                        isExpenseLocalCursor(it) && employee?.id == requested.employeeId
                    },
            )
        val page =
            directory.copy(
                items = employments,
                nextCursor =
                    if (remaining.size > employments.size) employments.last().employeeNumber
                    else null,
            )
        if (employee == null)
            return flowOf(
                AppResult.Success(
                    ExpenseSnapshot(
                        access,
                        checkpoint.generation,
                        ExpenseOverview(page, selection, ExpensePage(emptyList())),
                        requireNotNull(checkpoint.refreshedAt),
                        checkpoint.refreshAfter,
                    )
                )
            )
        return replicas
            .observe(access, selection)
            .filter { result ->
                result !is AppResult.Success ||
                    result.value.checkpoint?.generation == checkpoint.generation
            }
            .map { result ->
                when (result) {
                    is AppResult.Failed -> result
                    is AppResult.Success -> {
                        val published = requireNotNull(result.value.checkpoint)
                        AppResult.Success(
                            ExpenseSnapshot(
                                access,
                                published.generation,
                                ExpenseOverview(page, selection, result.value.page),
                                requireNotNull(published.refreshedAt),
                                published.refreshAfter,
                            )
                        )
                    }
                }
            }
    }

    /** A matching preceding snapshot remains readable until the full replica publishes. */
    private fun observeLegacy(
        access: ExpenseAccess,
        selection: ExpenseSelection,
    ): Flow<AppResult<ExpenseSnapshot<ExpenseOverview>?>> =
        cache.observe(access).map { loaded ->
            when (loaded) {
                is AppResult.Failed -> loaded
                is AppResult.Success ->
                    when (val owner = validateStoredExpense(access, loaded.value)) {
                        is AppResult.Failed -> owner
                        is AppResult.Success ->
                            AppResult.Success(
                                loaded.value?.takeIf {
                                    val previous = it.value.selection
                                    (selection.employeeId == null ||
                                        selection.employeeId == previous.employeeId) &&
                                        (selection.month == null ||
                                            selection.month == previous.month) &&
                                        selection.status == previous.status &&
                                        selection.after == previous.after &&
                                        selection.employmentAfter == previous.employmentAfter
                                }
                            )
                    }
            }
        }
}
