package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.*
import dev.fajar.starter.workforce.domain.overtime.usecases.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.*

class OvertimeCommandFixture(instant: () -> Instant = { Instant.parse("2026-10-12T13:00:00Z") }) {
    val planning = OvertimePlanningFixture(instant)
    val overtime = planning.overtime
    val base = planning.base
    val working = OvertimePlanWorkingFixture(overtime)
    val actionWorking = OvertimeActionWorkingFixture(overtime)
    val access = overtime.access
    val workspace = overtime.workspace
    val prepared =
        OvertimePreparation(
            access,
            planning.selection,
            planning.review,
            base.clock.now(),
            base.clock.now() + 60.seconds,
        )
    val interval = OvertimeIntervalInput(overtime.interval.startsAt, overtime.interval.endsAt, 0)
    val snapshot =
        OvertimeSnapshot(
            access,
            "generation",
            OvertimeRequestView(overtime.selection, overtime.details),
            base.clock.now(),
            base.clock.now() + 1.hours,
        )
    val records = MutableStateFlow<List<OvertimeCommandRecord>>(emptyList())
    val transmitted = mutableListOf<OvertimeCommand>()
    var beforeObserve: suspend () -> Unit = {}
    var beforeInsert: suspend () -> Unit = {}
    var beforeFind: suspend () -> Unit = {}
    var beforeRemove: suspend () -> Unit = {}
    var beforeUpdate: suspend (OvertimeCommandOutcome) -> Unit = {}
    var observers = 0
    var inserts = 0
    var pageReads = 0
    var updateFailure: Failure? = null
    var findFailure: Failure? = null
    var insertionResponseLost = false
    var pageCall: (suspend (String?) -> AppResult<OvertimePage<OvertimeCommandRecord>>)? = null
    var transmit: suspend (OvertimeCommand) -> AppResult<OvertimeCommandReceipt> = {
        AppResult.Success(OvertimeCommandReceipt(it.requestId, it.expectedReceiptVersion()))
    }

    val commands =
        object : OvertimeCommandRepository {
            override fun observeAll(access: WorkforceAccess) = flow {
                observers++
                try {
                    emitAll(
                        records.map { rows ->
                            beforeObserve()
                            AppResult.Success(
                                rows.filter {
                                    it.command.accountId == access.accountId &&
                                        it.command.companyId == access.companyId &&
                                        it.command.audience == access.audience
                                }
                            )
                        }
                    )
                } finally {
                    observers--
                }
            }

            override fun observe(
                access: WorkforceAccess,
                slot: OvertimeCommandSlot,
            ): Flow<AppResult<OvertimeCommandRecord?>> = flow {
                observers++
                try {
                    emitAll(
                        records.map { values ->
                            beforeObserve()
                            AppResult.Success(
                                values.singleOrNull {
                                    it.command.slot() == slot && it.command.belongsTo(access)
                                }
                            )
                        }
                    )
                } finally {
                    observers--
                }
            }

            override suspend fun find(
                access: WorkforceAccess,
                id: String,
            ): AppResult<OvertimeCommandRecord?> {
                beforeFind()
                findFailure?.let {
                    return AppResult.Failed(it)
                }
                return AppResult.Success(
                    records.value.singleOrNull {
                        it.command.id == id && it.command.belongsTo(access)
                    }
                )
            }

            override suspend fun page(
                access: WorkforceAccess,
                cursor: String?,
            ): AppResult<OvertimePage<OvertimeCommandRecord>> {
                pageReads++
                return pageCall?.invoke(cursor)
                    ?: AppResult.Success(
                        OvertimePage(records.value.filter { it.command.belongsTo(access) })
                    )
            }

            override suspend fun insert(
                access: WorkforceAccess,
                command: OvertimeCommand,
            ): AppResult<OvertimeCommandAdmission> {
                beforeInsert()
                if (
                    records.value.any {
                        it.command.belongsTo(access) && it.command.slot() == command.slot()
                    }
                )
                    return AppResult.Success(OvertimeCommandAdmission.OCCUPIED)
                inserts++
                records.value +=
                    OvertimeCommandRecord(command, 0, 0, OvertimeCommandOutcome.Pending)
                if (insertionResponseLost)
                    return AppResult.Failed(Failure(FailureKind.Storage, "Save response lost"))
                return AppResult.Success(OvertimeCommandAdmission.STORED)
            }

            override suspend fun update(
                access: WorkforceAccess,
                expected: OvertimeCommandRecord,
                outcome: OvertimeCommandOutcome,
                attempts: Int,
            ): AppResult<Boolean> {
                beforeUpdate(outcome)
                updateFailure?.let {
                    return AppResult.Failed(it)
                }
                if (expected !in records.value || !expected.command.belongsTo(access))
                    return AppResult.Success(false)
                records.value =
                    records.value.map {
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
                access: WorkforceAccess,
                expected: OvertimeCommandRecord,
            ): AppResult<Boolean> {
                beforeRemove()
                val found = expected in records.value && expected.command.belongsTo(access)
                if (found) records.value -= expected
                return AppResult.Success(found)
            }
        }
    val remote =
        object : OvertimeSubmissionRepository {
            override suspend fun send(
                sessionId: String,
                command: OvertimeCommand,
            ): AppResult<OvertimeCommandReceipt> {
                transmitted += command
                return transmit(command)
            }
        }
    val preparePlan =
        PrepareOvertimePlan(
            commands,
            overtime.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
            working.copies,
        )
    val prepareAction =
        PrepareOvertimeAction(
            commands,
            overtime.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
            actionWorking.copies,
        )
    val replay =
        ReplayOvertimeCommands(
            commands,
            remote,
            overtime.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val review =
        ReviewOvertimeCommand(
            commands,
            overtime.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
            working.copies,
            actionWorking.copies,
        )
    val observe =
        ObserveOvertimeCommand(
            commands,
            overtime.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )

    suspend fun queue(reason: String = "Inventory count", proof: OvertimePreparation = prepared) =
        preparePlan(workspace.id, proof, interval, reason)

    suspend fun queueAction(
        action: OvertimeRequestedAction = OvertimeRequestedAction.Withdraw,
        proof: OvertimeSnapshot<OvertimeRequestView> = snapshot,
    ) = prepareAction(workspace.id, proof, action, "Schedule changed")

    suspend fun send() = replay(workspace.id, records.value.single().command.id)

    suspend fun clear() = review(workspace.id, records.value.single().command.id)

    fun publish(version: Long = records.value.single().command.expectedReceiptVersion()) {
        val command = records.value.single().command
        overtime.detailRecords.value =
            AppResult.Success(
                snapshot.copy(
                    value =
                        OvertimeRequestView(
                            overtime.selection.copy(requestId = command.requestId),
                            overtime.details.copy(
                                request =
                                    overtime.request.copy(id = command.requestId, version = version)
                            ),
                        )
                )
            )
    }
}
