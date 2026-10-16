package dev.fajar.starter.leave.presentation

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.belongsTo
import dev.fajar.starter.leave.domain.repositories.*
import dev.fajar.starter.leave.domain.usecases.*
import dev.fajar.starter.leave.presentation.create.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

class LeaveCreateFixture(instant: () -> Instant = { Instant.parse("2026-10-12T01:00:00Z") }) {
    val request = LeaveRequestPresentationFixture(instant)
    val base = request.base
    val working = LeaveWorkingPresentationFixture(base)
    val target = LeaveCreateTarget(base.workspace.id, base.employee.id, "2026-10-15")
    val type =
        LeaveTypeOption(
            base.balance.typeId,
            "ANNUAL",
            "Annual leave",
            LocalDate(2026, 1, 1),
            true,
            true,
            false,
            true,
            0,
            0,
        )
    val records = MutableStateFlow<AppResult<LeaveSubmissionRecord?>>(AppResult.Success(null))
    val transmitted = mutableListOf<LeaveSubmissionCommand>()
    val previews = mutableListOf<LeaveDraftSelection>()
    val catalogs = mutableListOf<LeaveTypeSelection>()
    var observers = 0
    var subscriptions = 0
    var failNextObservation = false
    var beforeInsert: suspend () -> Unit = {}
    var canonical: LeaveRequestDetails? = null
    var typesCall: suspend (LeaveTypeSelection) -> AppResult<LeavePage<LeaveTypeOption>> = {
        AppResult.Success(LeavePage(listOf(type)))
    }
    var previewCall: suspend (LeaveDraftSelection) -> AppResult<LeaveRequestPreview> = {
        AppResult.Success(preview(it))
    }
    var transmit: suspend (LeaveSubmissionCommand) -> AppResult<LeaveSubmissionReceipt> =
        { command ->
            val calculated = preview(command.selection)
            canonical =
                request.details.copy(
                    id = command.requestId,
                    employeeId = command.selection.employeeId,
                    policy = calculated.policy,
                    days = calculated.days,
                    charged = calculated.charged,
                    reason = command.reason,
                    history =
                        LeavePage(
                            listOf(
                                LeaveChange(
                                    0,
                                    LeaveChangeKind.SUBMITTED,
                                    LeaveStatus.PENDING,
                                    base.clock.now(),
                                    command.reason,
                                )
                            )
                        ),
                )
            AppResult.Success(LeaveSubmissionReceipt(command.requestId, 0))
        }

    init {
        request.requestCall = {
            canonical?.let { AppResult.Success(it) }
                ?: AppResult.Failed(
                    Failure(
                        FailureKind.Unavailable,
                        "Not yet created",
                        code = "leave_request_not_found",
                    )
                )
        }
    }

    fun preview(selection: LeaveDraftSelection): LeaveRequestPreview {
        val charged =
            selection.days.filter {
                it.date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            }
        val amount = LeaveDays(charged.sumOf { it.portion.halfDays })
        return LeaveRequestPreview(
            selection.employeeId,
            base.clock.now(),
            "Asia/Jakarta",
            LeaveRequestPolicy(selection.typeId, type.code, 0, type.name, true, true, false),
            charged.map {
                LeaveDay(
                    it.date,
                    it.portion,
                    base.clock.now(),
                    base.clock.now() + 8.hours,
                    480,
                    it.portion.halfDays * 240,
                )
            },
            amount,
            (selection.days - charged.toSet()).map { it.date },
            listOf(
                LeavePreviewBalance(
                    2026,
                    LeaveDays(25),
                    LeaveDays(0),
                    LeaveDays(0),
                    amount,
                    LeaveDays(25 - amount.halfDays),
                    0,
                )
            ),
            "a".repeat(64),
        )
    }

    val commands =
        object : LeaveSubmissionCommandRepository {
            override fun observe(
                access: LeaveAccess,
                employeeId: String,
            ): Flow<AppResult<LeaveSubmissionRecord?>> = flow {
                observers++
                subscriptions++
                try {
                    if (failNextObservation) {
                        failNextObservation = false
                        emit(AppResult.Failed(Failure(FailureKind.Storage, "Source ended")))
                    } else emitAll(records)
                } finally {
                    observers--
                }
            }

            override suspend fun find(access: LeaveAccess, id: String) =
                when (val current = records.value) {
                    is AppResult.Failed -> current
                    is AppResult.Success ->
                        AppResult.Success(
                            current.value?.takeIf {
                                it.command.id == id && it.command.belongsTo(access)
                            }
                        )
                }

            override suspend fun page(access: LeaveAccess, cursor: String?) =
                when (val current = records.value) {
                    is AppResult.Failed -> current
                    is AppResult.Success ->
                        AppResult.Success(LeavePage(listOfNotNull(current.value)))
                }

            override suspend fun insert(
                access: LeaveAccess,
                command: LeaveSubmissionCommand,
            ): AppResult<LeaveCommandAdmission> {
                beforeInsert()
                if (records.value !is AppResult.Success) return records.value as AppResult.Failed
                if ((records.value as AppResult.Success).value != null)
                    return AppResult.Success(LeaveCommandAdmission.OCCUPIED)
                records.value =
                    AppResult.Success(
                        LeaveSubmissionRecord(command, 0, 0, LeaveCommandOutcome.Pending)
                    )
                return AppResult.Success(LeaveCommandAdmission.STORED)
            }

            override suspend fun update(
                access: LeaveAccess,
                expected: LeaveSubmissionRecord,
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

            override suspend fun remove(access: LeaveAccess, expected: LeaveSubmissionRecord) =
                AppResult.Success(
                    records.compareAndSet(AppResult.Success(expected), AppResult.Success(null))
                )
        }
    val remote =
        object : LeavePreparationRepository {
            override suspend fun types(
                access: LeaveAccess,
                selection: LeaveTypeSelection,
            ): AppResult<LeavePage<LeaveTypeOption>> {
                catalogs += selection
                return typesCall(selection)
            }

            override suspend fun preview(
                access: LeaveAccess,
                selection: LeaveDraftSelection,
            ): AppResult<LeaveRequestPreview> {
                previews += selection
                return previewCall(selection)
            }
        }
    val submissionRemote =
        object : LeaveSubmissionRepository {
            override suspend fun submit(
                sessionId: String,
                command: LeaveSubmissionCommand,
            ): AppResult<LeaveSubmissionReceipt> {
                transmitted += command
                return transmit(command)
            }
        }
    val replay =
        ReplayLeaveSubmission(
            commands,
            submissionRemote,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )

    fun model(selected: LeaveCreateTarget = target) =
        LeaveCreateViewModel(
            selected,
            LoadLeaveTypes(
                remote,
                base.sessions,
                base.workspaces,
                base.clock,
                base.build,
                base.replica,
            ),
            PreviewLeaveRequest(
                remote,
                base.sessions,
                base.workspaces,
                base.clock,
                base.build,
                base.replica,
            ),
            ObserveLeaveSubmission(
                commands,
                base.sessions,
                base.workspaces,
                base.clock,
                base.build,
                base.replica,
            ),
            PrepareLeaveSubmission(
                commands,
                base.sessions,
                base.workspaces,
                base.clock,
                base.build,
                base.replica,
                working.copies,
            ),
            replay,
            ReviewLeaveSubmission(
                commands,
                request.remote,
                request.cache,
                base.sessions,
                base.workspaces,
                base.clock,
                base.build,
                working.copies,
            ),
            request.refresh,
            LeaveSyncFixture(base).sync,
            working.open,
            working.save,
            working.discard,
        )
}
