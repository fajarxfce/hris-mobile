package dev.fajar.starter.workforce.presentation.overtime

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.*
import dev.fajar.starter.workforce.domain.overtime.usecases.*
import dev.fajar.starter.workforce.presentation.overtime.plan.*
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.*

class OvertimePlanFixture(
    dispatcher: CoroutineDispatcher,
    now: () -> Instant = { Instant.parse("2026-10-12T13:00:00Z") },
) {
    val base = OvertimePresentationFixture(now)
    val working = OvertimeWorkingPresentationFixture(base)

    init {
        val checkpoint =
            initialOvertimeCheckpoint(base.access, "plan-owned-directory", base.clock.now())
                .copy(
                    revision = 2,
                    phase = OvertimeSyncPhase.CHANGES,
                    cursor = "cursor/0",
                    published = true,
                    refreshedAt = base.clock.now(),
                )
        base.replicas.current = checkpoint
        base.replicas.published.value =
            AppResult.Success(
                OvertimeReplicaFixture.Published(checkpoint, base.directory, emptyList())
            )
    }

    val target =
        OvertimePlanTarget(base.workspace.id, base.employee.id, base.directory.asOf.toString())
    val retained = MutableStateFlow<AppResult<OvertimeCommandRecord?>>(AppResult.Success(null))
    val transmitted = mutableListOf<OvertimeCommand>()
    var reads = 0
    var observers = 0
    var terminalObservationFailure = false
    var beforeObserve: suspend () -> Unit = {}
    var beforeInsert: suspend () -> Unit = {}
    var read: suspend (OvertimePlanningSelection) -> AppResult<OvertimePlanningReview> =
        { selection ->
            AppResult.Success(
                OvertimePlanningReview(
                    base.employee,
                    selection.workDate,
                    base.directory.asOf,
                    base.directory.timezone,
                    WorkDay.Off(selection.workDate, CalendarOrigin.ROSTER, 0, null),
                    "a".repeat(64),
                )
            )
        }
    var transmit: suspend (OvertimeCommand) -> AppResult<OvertimeCommandReceipt> = { command ->
        base.detailsCall = { selection ->
            AppResult.Success(
                base.details.copy(
                    request =
                        base.request.copy(
                            id = selection.requestId,
                            workDate = command.workDate,
                            requested = (command.payload as OvertimeCommandPayload.Plan).requested,
                            reason = command.reason,
                        )
                )
            )
        }
        AppResult.Success(OvertimeCommandReceipt(command.requestId, 0))
    }
    val planning =
        object : OvertimePlanningRepository {
            override suspend fun review(
                access: WorkforceAccess,
                selection: OvertimePlanningSelection,
            ): AppResult<OvertimePlanningReview> {
                reads++
                return read(selection)
            }
        }
    val commands =
        object : OvertimeCommandRepository {
            override fun observe(
                access: WorkforceAccess,
                slot: OvertimeCommandSlot,
            ): Flow<AppResult<OvertimeCommandRecord?>> = flow {
                observers++
                try {
                    beforeObserve()
                    if (terminalObservationFailure) {
                        terminalObservationFailure = false
                        emit(overtimeFailure("fixture.storage", FailureKind.Storage))
                    } else emitAll(retained)
                } finally {
                    observers--
                }
            }

            override suspend fun find(access: WorkforceAccess, id: String) = retained.value

            override suspend fun page(access: WorkforceAccess, cursor: String?) =
                AppResult.Success(
                    OvertimePage(listOfNotNull((retained.value as? AppResult.Success)?.value))
                )

            override suspend fun insert(
                access: WorkforceAccess,
                command: OvertimeCommand,
            ): AppResult<OvertimeCommandAdmission> {
                beforeInsert()
                if ((retained.value as? AppResult.Success)?.value != null)
                    return AppResult.Success(OvertimeCommandAdmission.OCCUPIED)
                retained.value =
                    AppResult.Success(
                        OvertimeCommandRecord(command, 0, 0, OvertimeCommandOutcome.Pending)
                    )
                return AppResult.Success(OvertimeCommandAdmission.STORED)
            }

            override suspend fun update(
                access: WorkforceAccess,
                expected: OvertimeCommandRecord,
                outcome: OvertimeCommandOutcome,
                attempts: Int,
            ): AppResult<Boolean> =
                AppResult.Success(
                    retained.compareAndSet(
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
                access: WorkforceAccess,
                expected: OvertimeCommandRecord,
            ): AppResult<Boolean> =
                AppResult.Success(
                    retained.compareAndSet(AppResult.Success(expected), AppResult.Success(null))
                )
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
    private val prepare =
        PrepareOvertimePlan(
            commands,
            base.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
            working.copies,
            dispatcher,
        )
    private val observe =
        ObserveOvertimeCommand(
            commands,
            base.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    private val replay =
        ReplayOvertimeCommands(
            commands,
            remote,
            base.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    private val review =
        ReviewOvertimeCommand(
            commands,
            base.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
            working.copies,
        )
    private val reviewSchedule =
        ReviewOvertimeSchedule(
            base.workforce,
            planning,
            base.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )

    fun create(store: ViewModelStore) =
        OvertimePlanViewModel(
                target,
                base.clock,
                reviewSchedule,
                observe,
                prepare,
                replay,
                review,
                base.refreshRequest,
                base.sync,
                working.open,
                working.save,
                working.discard,
            )
            .also { store.put("plan", it) }

    fun fill(viewModel: OvertimePlanViewModel) {
        viewModel.onEvent(OvertimePlanEvent.TimePickerRequested(OvertimeTimeField.START))
        viewModel.onEvent(OvertimePlanEvent.HoursChanged("18"))
        viewModel.onEvent(OvertimePlanEvent.MinutesChanged("30"))
        viewModel.onEvent(OvertimePlanEvent.TimeConfirmed)
        viewModel.onEvent(OvertimePlanEvent.TimePickerRequested(OvertimeTimeField.END))
        viewModel.onEvent(OvertimePlanEvent.HoursChanged("20"))
        viewModel.onEvent(OvertimePlanEvent.MinutesChanged("30"))
        viewModel.onEvent(OvertimePlanEvent.TimeConfirmed)
        viewModel.onEvent(OvertimePlanEvent.BreakChanged("15"))
        viewModel.onEvent(OvertimePlanEvent.ReasonChanged("Inventory count"))
    }
}
