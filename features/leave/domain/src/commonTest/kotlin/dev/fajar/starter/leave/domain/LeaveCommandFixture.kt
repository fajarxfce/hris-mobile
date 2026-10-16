package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.repositories.*
import dev.fajar.starter.leave.domain.usecases.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.*

class LeaveCommandFixture(instant: () -> Instant = { Instant.parse("2026-10-12T01:00:00Z") }) {
    val request = LeaveRequestFixture(instant)
    val base = request.base
    val records = MutableStateFlow<List<LeaveCommandRecord>>(emptyList())
    val transmitted = mutableListOf<LeaveCommand>()
    var beforeObserve: suspend () -> Unit = {}
    var beforeInsert: suspend () -> Unit = {}
    var beforeRemove: suspend () -> Unit = {}
    var beforeUpdate: suspend (LeaveCommandOutcome) -> Unit = {}
    var observers = 0
    var updateFailure: Failure? = null
    var transmit: suspend (LeaveCommand) -> AppResult<LeaveActionReceipt> = {
        AppResult.Success(LeaveActionReceipt(it.requestId, it.expectedVersion + 1))
    }
    val access =
        (admitLeave(base.workspace.id, base.workspace, base.session, base.build, base.clock.now())
                as AppResult.Success)
            .value

    init {
        publish()
    }

    fun publish(details: LeaveRequestDetails = request.details) {
        request.records.value =
            AppResult.Success(
                LeaveRequestSnapshot(
                    access,
                    "generation-${details.version}",
                    request.selection,
                    details,
                    base.clock.now(),
                    base.clock.now() + 1.hours,
                )
            )
    }

    val commands =
        object : LeaveCommandRepository {
            override fun observe(
                access: LeaveAccess,
                requestId: String,
            ): Flow<AppResult<LeaveCommandRecord?>> = flow {
                observers++
                try {
                    emitAll(
                        records.map { rows ->
                            beforeObserve()
                            AppResult.Success(
                                rows.singleOrNull {
                                    it.command.requestId == requestId &&
                                        it.command.belongsTo(access)
                                }
                            )
                        }
                    )
                } finally {
                    observers--
                }
            }

            override suspend fun find(access: LeaveAccess, id: String) =
                AppResult.Success(
                    records.value.singleOrNull {
                        it.command.id == id && it.command.belongsTo(access)
                    }
                )

            override suspend fun page(access: LeaveAccess, cursor: String?) =
                AppResult.Success(LeavePage(records.value.filter { it.command.belongsTo(access) }))

            override suspend fun insert(
                access: LeaveAccess,
                command: LeaveCommand,
            ): AppResult<LeaveCommandAdmission> {
                beforeInsert()
                if (
                    records.value.any {
                        it.command.requestId == command.requestId && it.command.belongsTo(access)
                    }
                )
                    return AppResult.Success(LeaveCommandAdmission.OCCUPIED)
                records.value += LeaveCommandRecord(command)
                return AppResult.Success(LeaveCommandAdmission.STORED)
            }

            override suspend fun update(
                access: LeaveAccess,
                expected: LeaveCommandRecord,
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
                expected: LeaveCommandRecord,
            ): AppResult<Boolean> {
                beforeRemove()
                val found = expected in records.value
                records.value -= expected
                return AppResult.Success(found)
            }
        }
    val remote =
        object : LeaveActionRepository {
            override suspend fun act(
                sessionId: String,
                command: LeaveCommand,
            ): AppResult<LeaveActionReceipt> {
                transmitted += command
                return transmit(command)
            }
        }
    val prepare =
        PrepareLeaveAction(
            commands,
            request.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val replay =
        ReplayLeaveAction(commands, remote, base.sessions, base.workspaces, base.clock, base.build)
    val review =
        ReviewLeaveAction(
            commands,
            request.remote,
            request.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val observe =
        ObserveLeaveAction(
            commands,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
            base.replica,
        )

    suspend fun queue(
        action: LeaveRequestAction = LeaveRequestAction.WITHDRAW,
        version: Long = request.details.version,
        reason: String = "Plans changed",
    ) = prepare(base.workspace.id, request.details.id, version, action, reason)

    suspend fun send() = replay(base.workspace.id, records.value.single().command.id)
}
