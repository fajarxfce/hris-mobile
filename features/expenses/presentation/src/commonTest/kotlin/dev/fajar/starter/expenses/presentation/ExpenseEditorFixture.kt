package dev.fajar.starter.expenses.presentation

import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.repositories.*
import dev.fajar.starter.expenses.domain.usecases.*
import dev.fajar.starter.expenses.presentation.draft.*
import dev.fajar.starter.expenses.presentation.item.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.*
import kotlinx.datetime.LocalDate

class ExpenseEditorFixture(val f: ExpenseFixture = ExpenseFixture()) {
    constructor(instant: () -> Instant) : this(ExpenseFixture(instant))

    val preparation =
        ExpenseDraftPreparation(
            f.access,
            f.employee,
            f.directory.asOf,
            null,
            f.clock.now(),
            f.clock.now() + 60.seconds,
        )
    val input = ExpenseDraftInput(f.draft.title, f.draft.description, f.draft.lines, "Saved")
    val values = MutableStateFlow<List<ExpenseCommandRecord>>(emptyList())
    var beforeRead: suspend () -> Unit = {}
    var beforeInsert: suspend () -> Unit = {}
    var beforeUpdate: suspend (ExpenseCommandRecord, ExpenseCommandOutcome) -> Unit = { _, _ -> }
    var beforeRemove: suspend () -> Unit = {}
    var failUpdates = false
    var observers = 0
    var insertions = 0
    var updates = 0
    var removals = 0
    val sent = mutableListOf<ExpenseCommand>()
    var transport: suspend (ExpenseCommand) -> AppResult<ExpenseCommandReceipt> = {
        publish(it)
        AppResult.Success(ExpenseCommandReceipt(it.claimId, it.expectedReceiptVersion()))
    }
    val remote =
        object : ExpenseSubmissionRepository {
            override suspend fun send(
                sessionId: String,
                command: ExpenseCommand,
            ): AppResult<ExpenseCommandReceipt> {
                check(sessionId == f.session.id)
                sent.add(command)
                return transport(command)
            }
        }
    val commands =
        object : ExpenseCommandRepository {
            override fun observe(access: ExpenseAccess, slot: ExpenseCommandSlot) =
                flow<AppResult<ExpenseCommandRecord?>> {
                    observers++
                    try {
                        beforeRead()
                        emitAll(
                            values.map { records ->
                                AppResult.Success(records.firstOrNull { it.command.slot() == slot })
                            }
                        )
                    } finally {
                        observers--
                    }
                }

            override suspend fun find(
                access: ExpenseAccess,
                id: String,
            ): AppResult<ExpenseCommandRecord?> {
                beforeRead()
                return AppResult.Success(values.value.firstOrNull { it.command.id == id })
            }

            override suspend fun page(
                access: ExpenseAccess,
                cursor: String?,
            ): AppResult<ExpensePage<ExpenseCommandRecord>> =
                AppResult.Success(ExpensePage(values.value))

            override suspend fun insert(
                access: ExpenseAccess,
                command: ExpenseCommand,
            ): AppResult<ExpenseCommandAdmission> {
                beforeInsert()
                if (values.value.any { it.command.slot() == command.slot() })
                    return AppResult.Success(ExpenseCommandAdmission.OCCUPIED)
                insertions++
                values.value += ExpenseCommandRecord(command, 0, 0, ExpenseCommandOutcome.Pending)
                return AppResult.Success(ExpenseCommandAdmission.STORED)
            }

            override suspend fun update(
                access: ExpenseAccess,
                expected: ExpenseCommandRecord,
                outcome: ExpenseCommandOutcome,
                attempts: Int,
            ): AppResult<Boolean> {
                beforeUpdate(expected, outcome)
                if (failUpdates) return expenseFailure("fixture.storage", FailureKind.Storage)
                if (expected !in values.value) return AppResult.Success(false)
                updates++
                values.value =
                    values.value.map {
                        if (it == expected)
                            it.copy(
                                revision = it.revision + 1,
                                attempts = attempts,
                                outcome = outcome,
                            )
                        else it
                    }
                return AppResult.Success(true)
            }

            override suspend fun remove(
                access: ExpenseAccess,
                expected: ExpenseCommandRecord,
            ): AppResult<Boolean> {
                beforeRemove()
                if (expected !in values.value) return AppResult.Success(false)
                removals++
                values.value -= expected
                return AppResult.Success(true)
            }
        }
    val prepare = PrepareExpenseDraft(commands, f.sessions, f.workspaces, f.clock, f.build)
    val action = PrepareExpenseAction(commands, f.sessions, f.workspaces, f.clock, f.build)
    val replay = ReplayExpenseCommands(commands, remote, f.sessions, f.workspaces, f.clock, f.build)
    val review = ReviewExpenseCommand(commands, f.cache, f.sessions, f.workspaces, f.clock, f.build)
    val observe = ObserveExpenseCommand(commands, f.sessions, f.workspaces, f.clock, f.build)

    suspend fun save(edited: Boolean = false): AppResult<ExpenseCommandRecord> =
        prepare(
            f.workspace.id,
            if (edited) preparation.copy(claim = f.claim) else preparation,
            input,
        )

    suspend fun details(): ExpenseSnapshot<ExpenseView> {
        check(f.load(true) is AppResult.Success)
        return (f.detailRecords.value as AppResult.Success).value!!
    }

    var currentClaim = f.claim
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
            0,
            0,
        )
    val center = ExpenseCostCenter("dddddddd-dddd-dddd-dddd-dddddddddddd", "CC01", "Operations", 0)
    var categoryCall: suspend (LocalDate, String?) -> AppResult<ExpensePage<ExpenseCategory>> =
        { _, _ ->
            AppResult.Success(ExpensePage(listOf(category)))
        }
    var centerCall: suspend (String, String?) -> AppResult<ExpensePage<ExpenseCostCenter>> =
        { _, _ ->
            AppResult.Success(ExpensePage(listOf(center)))
        }
    var referenceCall: suspend (ExpenseReferenceQuery) -> AppResult<ExpenseReferenceValues> =
        { query ->
            AppResult.Success(
                ExpenseReferenceValues(
                    query.employeeId,
                    query.categoryIds.map { ExpenseLabelReference(it, "TRAVEL", "Travel", true) },
                    query.costCenterIds.map {
                        ExpenseLabelReference(it, "CC01", "Operations", true)
                    },
                    query.receiptRevisionIds.map { ExpenseReceiptReference(it) },
                )
            )
        }
    var categoryReads = 0
    var centerReads = 0
    var referenceReads = 0
    val catalogs =
        object : ExpensePreparationRepository {
            override suspend fun categories(
                access: ExpenseAccess,
                asOf: LocalDate,
                after: String?,
            ): AppResult<ExpensePage<ExpenseCategory>> {
                categoryReads++
                return categoryCall(asOf, after)
            }

            override suspend fun costCenters(
                access: ExpenseAccess,
                query: String,
                after: String?,
            ): AppResult<ExpensePage<ExpenseCostCenter>> {
                centerReads++
                return centerCall(query, after)
            }

            override suspend fun references(
                access: ExpenseAccess,
                query: ExpenseReferenceQuery,
            ): AppResult<ExpenseReferenceValues> {
                referenceReads++
                return referenceCall(query)
            }
        }
    val loadPreparation =
        LoadExpenseDraftPreparation(f.remote, f.sessions, f.workspaces, f.clock, f.build)
    val references =
        LoadExpenseDraftReferences(catalogs, f.sessions, f.workspaces, f.clock, f.build)

    init {
        f.claimCall = { AppResult.Success(currentClaim) }
        f.historyCall = {
            AppResult.Success(
                ExpensePage(
                    listOf(
                        ExpenseChange(
                            currentClaim.version,
                            currentClaim.draft.revision,
                            currentClaim.status,
                            ExpenseChangeKind.DRAFT_SAVED,
                            f.session.user.id,
                            "Saved",
                            f.clock.now(),
                            null,
                        )
                    )
                )
            )
        }
    }

    fun publish(command: ExpenseCommand) {
        val payload = command.payload as? ExpenseCommandPayload.SaveDraft ?: return
        val version = command.expectedReceiptVersion()
        val cents = payload.lines.sumOf { requireNotNull(expenseMinorUnits(it.amount)) }
        val total =
            DecimalAmount.parse("${cents / 100}.${(cents % 100).toString().padStart(2, '0')}")
        val draft =
            f.draft.copy(
                revision =
                    if (payload.expectedVersion == null) 0 else currentClaim.draft.revision + 1,
                title = payload.title,
                description = payload.description,
                lines = payload.lines,
                total = total,
                reason = command.reason,
                recordedAt = f.clock.now(),
            )
        currentClaim =
            f.claim.copy(
                id = command.claimId,
                version = version,
                draft = draft,
                status = ExpenseStatus.DRAFT,
            )
    }

    fun draftTarget(editing: Boolean = false) =
        ExpenseDraftTarget(f.workspace.id, f.employee.id, f.claim.id.takeIf { editing })

    fun draftModel(target: ExpenseDraftTarget = draftTarget()) =
        ExpenseDraftViewModel(
            target,
            loadPreparation,
            references,
            observe,
            prepare,
            replay,
            review,
            f.refreshDetail,
        )

    fun itemTarget(existing: Boolean = false) =
        ExpenseItemTarget(f.workspace.id, preparation, f.line.takeIf { existing }, null)

    fun itemModel(target: ExpenseItemTarget = itemTarget()) =
        ExpenseItemViewModel(
            target,
            loadPreparation,
            references,
            LoadExpenseCategories(catalogs, f.sessions, f.workspaces, f.clock, f.build),
            LoadExpenseCostCenters(catalogs, f.sessions, f.workspaces, f.clock, f.build),
            PrepareExpenseLine(),
            f.clock,
        )
}
