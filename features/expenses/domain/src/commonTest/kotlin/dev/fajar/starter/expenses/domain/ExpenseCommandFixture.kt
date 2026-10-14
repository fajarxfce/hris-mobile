package dev.fajar.starter.expenses.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.repositories.*
import dev.fajar.starter.expenses.domain.usecases.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.*

class ExpenseCommandFixture(instant: () -> Instant = { Instant.parse("2026-10-13T00:00:00Z") }) {
    val f = ExpenseFixture(instant)
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
    val copies = ExpenseWorkingCopyFake()
    val prepare = PrepareExpenseDraft(commands, f.sessions, f.workspaces, f.clock, f.build)
    val action = PrepareExpenseAction(commands, f.sessions, f.workspaces, f.clock, f.build)
    val replay = ReplayExpenseCommands(commands, remote, f.sessions, f.workspaces, f.clock, f.build)
    val review =
        ReviewExpenseCommand(
            commands,
            f.replicas,
            f.sessions,
            f.workspaces,
            f.clock,
            f.build,
            copies,
        )
    val observe =
        ObserveExpenseCommand(commands, f.sessions, f.workspaces, f.clock, f.build, f.replicas)

    suspend fun save(edited: Boolean = false): AppResult<ExpenseCommandRecord> =
        prepare(
            f.workspace.id,
            if (edited) preparation.copy(claim = f.claim) else preparation,
            input,
        )

    suspend fun details(): ExpenseSnapshot<ExpenseView> {
        check(f.load(true) is AppResult.Success)
        return (f.observeDetail(f.workspace.id, f.claim.id).first() as AppResult.Success).value!!
    }
}
