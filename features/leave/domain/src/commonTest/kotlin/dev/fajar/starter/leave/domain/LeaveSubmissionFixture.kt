package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.repositories.*
import dev.fajar.starter.leave.domain.usecases.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.*

class LeaveSubmissionFixture(instant: () -> Instant = { Instant.parse("2026-10-12T01:00:00Z") }) {
    val request = LeaveRequestFixture(instant)
    val base = request.base
    val preparation = LeavePreparationFixture(base)
    val access =
        (admitLeave(base.workspace.id, base.workspace, base.session, base.build, base.clock.now())
                as AppResult.Success)
            .value
    val prepared =
        LeavePreparation(
            access,
            preparation.selected,
            preparation.value,
            base.clock.now(),
            base.clock.now() + 60.seconds,
        )
    val records = MutableStateFlow<List<LeaveSubmissionRecord>>(emptyList())
    val transmitted = mutableListOf<LeaveSubmissionCommand>()
    var beforeObserve: suspend () -> Unit = {}
    var beforeInsert: suspend () -> Unit = {}
    var beforeFind: suspend () -> Unit = {}
    var beforeRemove: suspend () -> Unit = {}
    var beforeUpdate: suspend (LeaveCommandOutcome) -> Unit = {}
    var observers = 0
    var inserts = 0
    var updateFailure: Failure? = null
    var findFailure: Failure? = null
    var insertionResponseLost = false
    var transmit: suspend (LeaveSubmissionCommand) -> AppResult<LeaveSubmissionReceipt> = {
        AppResult.Success(LeaveSubmissionReceipt(it.requestId, 0))
    }

    val commands =
        object : LeaveSubmissionCommandRepository {
            override fun observe(
                access: LeaveAccess,
                employeeId: String,
            ): Flow<AppResult<LeaveSubmissionRecord?>> = flow {
                observers++
                try {
                    emitAll(
                        records.map { values ->
                            beforeObserve()
                            AppResult.Success(
                                values.singleOrNull {
                                    it.command.selection.employeeId == employeeId &&
                                        it.command.belongsTo(access)
                                }
                            )
                        }
                    )
                } finally {
                    observers--
                }
            }

            override suspend fun find(
                access: LeaveAccess,
                id: String,
            ): AppResult<LeaveSubmissionRecord?> {
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

            override suspend fun page(access: LeaveAccess, cursor: String?) =
                AppResult.Success(LeavePage(records.value.filter { it.command.belongsTo(access) }))

            override suspend fun insert(
                access: LeaveAccess,
                command: LeaveSubmissionCommand,
            ): AppResult<LeaveCommandAdmission> {
                beforeInsert()
                if (
                    records.value.any {
                        it.command.selection.employeeId == command.selection.employeeId &&
                            it.command.belongsTo(access)
                    }
                )
                    return AppResult.Success(LeaveCommandAdmission.OCCUPIED)
                inserts++
                records.value += LeaveSubmissionRecord(command, 0, 0, LeaveCommandOutcome.Pending)
                if (insertionResponseLost)
                    return AppResult.Failed(Failure(FailureKind.Storage, "Save response lost"))
                return AppResult.Success(LeaveCommandAdmission.STORED)
            }

            override suspend fun update(
                access: LeaveAccess,
                expected: LeaveSubmissionRecord,
                outcome: LeaveCommandOutcome,
                attempts: Int,
            ): AppResult<Boolean> {
                beforeUpdate(outcome)
                updateFailure?.let {
                    return AppResult.Failed(it)
                }
                if (expected !in records.value) return AppResult.Success(false)
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
                access: LeaveAccess,
                expected: LeaveSubmissionRecord,
            ): AppResult<Boolean> {
                beforeRemove()
                val found = expected in records.value
                records.value -= expected
                return AppResult.Success(found)
            }
        }
    val remote =
        object : LeaveSubmissionRepository {
            override suspend fun submit(
                sessionId: String,
                command: LeaveSubmissionCommand,
            ): AppResult<LeaveSubmissionReceipt> {
                transmitted += command
                return transmit(command)
            }
        }
    val prepare =
        PrepareLeaveSubmission(commands, base.sessions, base.workspaces, base.clock, base.build)
    val replay =
        ReplayLeaveSubmission(
            commands,
            remote,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val review =
        ReviewLeaveSubmission(
            commands,
            request.remote,
            request.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val observe =
        ObserveLeaveSubmission(commands, base.sessions, base.workspaces, base.clock, base.build)

    suspend fun queue(
        reason: String = "Personal leave",
        preview: LeavePreparation = prepared,
        attachments: List<String> = emptyList(),
    ) = prepare(base.workspace.id, preview, reason, attachments)

    suspend fun send() = replay(base.workspace.id, records.value.single().command.id)

    suspend fun clear() = review(base.workspace.id, records.value.single().command.id)

    fun publish() {
        val command = records.value.single().command
        val details =
            request.details.copy(
                id = command.requestId,
                employeeId = command.selection.employeeId,
                policy = preparation.value.policy,
                reason = command.reason,
                days = preparation.value.days,
                charged = command.charged,
            )
        request.requestCall = { AppResult.Success(details) }
        request.records.value =
            AppResult.Success(
                LeaveRequestSnapshot(
                    access,
                    "generation",
                    LeaveRequestSelection(command.requestId),
                    details,
                    base.clock.now(),
                    base.clock.now() + 1.hours,
                )
            )
    }
}
