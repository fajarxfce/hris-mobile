package dev.fajar.starter.expenses.domain

import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.repositories.ExpensePreparationRepository
import dev.fajar.starter.expenses.domain.usecases.*
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

enum class ExpensePreparationKind {
    DRAFT,
    CATEGORIES,
    COST_CENTERS,
    REFERENCES,
}

class ExpensePreparationFixture(
    instant: () -> Instant = { Instant.parse("2026-10-13T00:00:00Z") }
) {
    val f = ExpenseFixture(instant)
    val category =
        ExpenseCategory(
            f.line.categoryId,
            "TRAVEL",
            "Travel",
            LocalDate(2026, 1, 1),
            DecimalAmount.parse("500000"),
            DecimalAmount.parse("2000000"),
            true,
            true,
            30,
            ExpenseContractKind.entries.toSet(),
            true,
            2,
            1,
        )
    val center = ExpenseCostCenter("dddddddd-dddd-dddd-dddd-dddddddddddd", "CC01", "Operations", 3)
    var beforeRead: suspend () -> Unit = {}
    var referenceQuery: ExpenseReferenceQuery? = null
    var referenceValues: ExpenseReferenceValues? = null
    var categoryQuery: Pair<LocalDate, String?>? = null
    var centerQuery: Pair<String, String?>? = null
    var reads = 0
    var failure: AppResult.Failed? = null
    val remote =
        object : ExpensePreparationRepository {
            override suspend fun references(
                access: ExpenseAccess,
                query: ExpenseReferenceQuery,
            ): AppResult<ExpenseReferenceValues> {
                reads++
                referenceQuery = query
                beforeRead()
                return failure
                    ?: AppResult.Success(
                        referenceValues
                            ?: ExpenseReferenceValues(
                                query.employeeId,
                                query.categoryIds.map { ExpenseLabelReference(it) },
                                query.costCenterIds.map { ExpenseLabelReference(it) },
                                query.receiptRevisionIds.map { ExpenseReceiptReference(it) },
                            )
                    )
            }

            override suspend fun categories(
                access: ExpenseAccess,
                asOf: LocalDate,
                after: String?,
            ): AppResult<ExpensePage<ExpenseCategory>> {
                reads++
                categoryQuery = asOf to after
                beforeRead()
                return failure ?: AppResult.Success(ExpensePage(listOf(category)))
            }

            override suspend fun costCenters(
                access: ExpenseAccess,
                query: String,
                after: String?,
            ): AppResult<ExpensePage<ExpenseCostCenter>> {
                reads++
                centerQuery = query to after
                beforeRead()
                return failure ?: AppResult.Success(ExpensePage(listOf(center)))
            }
        }
    val draft = LoadExpenseDraftPreparation(f.remote, f.sessions, f.workspaces, f.clock, f.build)
    val categories = LoadExpenseCategories(remote, f.sessions, f.workspaces, f.clock, f.build)
    val references = LoadExpenseDraftReferences(remote, f.sessions, f.workspaces, f.clock, f.build)
    val centers = LoadExpenseCostCenters(remote, f.sessions, f.workspaces, f.clock, f.build)

    init {
        f.directoryCall = {
            reads++
            beforeRead()
            failure ?: AppResult.Success(f.directory)
        }
    }

    suspend fun load(kind: ExpensePreparationKind): AppResult<*> =
        when (kind) {
            ExpensePreparationKind.REFERENCES ->
                references(f.workspace.id, f.employee.id, listOf(f.line))
            ExpensePreparationKind.DRAFT -> draft(f.workspace.id, f.employee.id, f.claim.id)
            ExpensePreparationKind.CATEGORIES ->
                categories(f.workspace.id, f.directory.asOf, "PREVIOUS")
            ExpensePreparationKind.COST_CENTERS ->
                centers(f.workspace.id, "  Operations  ", "COST_CENTER:CC00")
        }
}
