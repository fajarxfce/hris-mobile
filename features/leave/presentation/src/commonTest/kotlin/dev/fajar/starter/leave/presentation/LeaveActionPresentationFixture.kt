package dev.fajar.starter.leave.presentation

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.repositories.*
import dev.fajar.starter.leave.domain.usecases.*
import dev.fajar.starter.leave.presentation.detail.*
import kotlinx.coroutines.flow.*

class LeaveActionPresentationFixture(val request: LeaveRequestPresentationFixture) {
    private val base = request.base
    val records = MutableStateFlow<AppResult<LeaveCommandRecord?>>(AppResult.Success(null))
    val transmitted = mutableListOf<LeaveCommand>()
    var canonical = request.details
    var observers = 0
    var subscriptions = 0
    var removals = 0
    var failNextObservation = false
    var beforeInsert: suspend () -> Unit = {}
    var insertFailure: Failure? = null
    var transmit: suspend (LeaveCommand) -> AppResult<LeaveActionReceipt> = { command ->
        canonical =
            canonical.copy(
                version = command.expectedVersion + 1,
                status =
                    when (command.action) {
                        LeaveRequestAction.WITHDRAW -> LeaveStatus.CANCELLED
                        LeaveRequestAction.REQUEST_CANCELLATION -> LeaveStatus.CANCELLATION_PENDING
                        LeaveRequestAction.WITHDRAW_CANCELLATION -> LeaveStatus.APPROVED
                    },
                availableActions =
                    when (command.action) {
                        LeaveRequestAction.WITHDRAW -> emptySet()
                        LeaveRequestAction.REQUEST_CANCELLATION -> setOf(LeaveAction.WITHDRAW)
                        LeaveRequestAction.WITHDRAW_CANCELLATION ->
                            setOf(LeaveAction.REQUEST_CANCELLATION)
                    },
            )
        AppResult.Success(LeaveActionReceipt(command.requestId, command.expectedVersion + 1))
    }

    init {
        request.requestCall = { AppResult.Success(canonical) }
    }

    val commands =
        object : LeaveCommandRepository {
            override fun observe(
                access: LeaveAccess,
                requestId: String,
            ): Flow<AppResult<LeaveCommandRecord?>> = flow {
                observers++
                subscriptions++
                try {
                    if (failNextObservation) {
                        failNextObservation = false
                        emit(AppResult.Failed(Failure(FailureKind.Storage, "Storage unavailable")))
                    } else emitAll(records)
                } finally {
                    observers--
                }
            }

            override suspend fun find(
                access: LeaveAccess,
                id: String,
            ): AppResult<LeaveCommandRecord?> =
                when (val result = records.value) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(result.value?.takeIf { it.command.id == id })
                }

            override suspend fun page(
                access: LeaveAccess,
                cursor: String?,
            ): AppResult<LeavePage<LeaveCommandRecord>> =
                when (val result = records.value) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(LeavePage(listOfNotNull(result.value)))
                }

            override suspend fun insert(
                access: LeaveAccess,
                command: LeaveCommand,
            ): AppResult<LeaveCommandAdmission> {
                beforeInsert()
                insertFailure?.let {
                    return AppResult.Failed(it)
                }
                val current = records.value
                if (current is AppResult.Failed) return current
                if ((current as AppResult.Success).value != null)
                    return AppResult.Success(LeaveCommandAdmission.OCCUPIED)
                records.value = AppResult.Success(LeaveCommandRecord(command))
                return AppResult.Success(LeaveCommandAdmission.STORED)
            }

            override suspend fun update(
                access: LeaveAccess,
                expected: LeaveCommandRecord,
                outcome: LeaveCommandOutcome,
                attempts: Int,
            ) =
                AppResult.Success(
                    records.compareAndSet(
                        AppResult.Success(expected),
                        AppResult.Success(
                            expected.copy(
                                revision = expected.revision + 1,
                                attempts = attempts,
                                outcome = outcome,
                            )
                        ),
                    )
                )

            override suspend fun remove(
                access: LeaveAccess,
                expected: LeaveCommandRecord,
            ): AppResult<Boolean> {
                val removed =
                    records.compareAndSet(AppResult.Success(expected), AppResult.Success(null))
                if (removed) removals++
                return AppResult.Success(removed)
            }
        }
    private val remote =
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
    val observe =
        ObserveLeaveAction(
            commands,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
            base.replica,
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

    fun model() =
        LeaveRequestViewModel(
            LeaveRequestTarget(base.workspace.id, request.details.id),
            request.observe,
            request.refresh,
            observe,
            prepare,
            replay,
            review,
            LeaveSyncFixture(base).sync,
        )
}
