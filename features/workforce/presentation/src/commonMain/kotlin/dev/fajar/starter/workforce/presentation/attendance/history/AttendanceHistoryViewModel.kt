package dev.fajar.starter.workforce.presentation.attendance.history

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.usecases.*
import dev.fajar.starter.workforce.domain.policies.calendarFailureInvalidatesCache
import dev.fajar.starter.workforce.presentation.attendance.history.mappers.*
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.number
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class AttendanceHistoryViewModel(
    @InjectedParam private val workspaceId: String,
    private val observe: ObserveAttendance,
    private val refresh: RefreshAttendance,
) :
    MviViewModel<AttendanceHistoryState, AttendanceHistoryEvent, AttendanceHistoryEffect>(
        AttendanceHistoryState()
    ) {
    private var observation: Job? = null
    private var acquisition: Job? = null

    init {
        on<AttendanceHistoryEvent.Entered>(::onEntered)
        on<AttendanceHistoryEvent.Stopped>(::onStopped)
        on<AttendanceHistoryEvent.RefreshRequested>(::onRefreshRequested)
        on<AttendanceHistoryEvent.BackRequested>(::onBackRequested)
        on<AttendanceHistoryEvent.VerificationRequested>(::onVerificationRequested)
        on<AttendanceHistoryEvent.DateFilterRequested>(::onDateFilterRequested)
        on<AttendanceHistoryEvent.DateFilterDismissed>(::onDateFilterDismissed)
        on<AttendanceHistoryEvent.MonthRequested>(::onMonthRequested)
        on<AttendanceHistoryEvent.PickerYearRequested>(::onPickerYearRequested)
        on<AttendanceHistoryEvent.PickerMonthSelected>(::onPickerMonthSelected)
        on<AttendanceHistoryEvent.EmploymentSelected>(::onEmploymentSelected)
        on<AttendanceHistoryEvent.EmploymentPageRequested>(::onEmploymentPageRequested)
        on<AttendanceHistoryEvent.DaySelected>(::onDaySelected)
        on<AttendanceHistoryEvent.DayDismissed>(::onDayDismissed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun onEntered(event: AttendanceHistoryEvent.Entered) {
        if (observation?.isActive == true) return
        updateState { it.copy(loading = true) }
        observation =
            viewModelScope.launch {
                state
                    .map {
                        it.selection to
                            (it.cacheFailure?.let(::calendarFailureInvalidatesCache) == true ||
                                it.refreshFailure?.let(::calendarFailureInvalidatesCache) == true)
                    }
                    .distinctUntilChanged()
                    .flatMapLatest { (requested, restricted) ->
                        if (restricted) emptyFlow()
                        else observe(workspaceId, AttendanceWindow.MONTHLY).map { requested to it }
                    }
                    .collect { (requested, result) ->
                        ensureActive()
                        if (
                            requested != state.value.selection ||
                                state.value.cacheFailure?.let(::calendarFailureInvalidatesCache) ==
                                    true ||
                                state.value.refreshFailure?.let(
                                    ::calendarFailureInvalidatesCache
                                ) == true
                        )
                            return@collect
                        when (result) {
                            is AppResult.Failed ->
                                updateState {
                                    it.copy(
                                        content = null,
                                        selectedDay = null,
                                        picker = null,
                                        loading = false,
                                        cacheFailure = result.failure,
                                        needsVerification =
                                            result.failure.code in verificationCodes ||
                                                it.refreshFailure?.code in verificationCodes,
                                    )
                                }
                            is AppResult.Success -> {
                                val content = result.value.toHistoryContent(Clock.System.now())
                                if (
                                    content == null ||
                                        state.value.selection.matches(content.snapshot)
                                ) {
                                    updateState {
                                        it.copy(
                                            selection =
                                                if (
                                                    !it.refreshing &&
                                                        it.refreshFailure != null &&
                                                        it.selection.from == null &&
                                                        content != null
                                                )
                                                    it.selection.copy(
                                                        employeeId = content.snapshot.employeeId,
                                                        from = content.snapshot.period.from,
                                                    )
                                                else it.selection,
                                            content = content,
                                            loading = false,
                                            cacheFailure = null,
                                            month =
                                                content?.snapshot?.let { snapshot ->
                                                    attendanceMonthFilter(
                                                        snapshot.period.from,
                                                        snapshot.directory.asOf,
                                                    )
                                                } ?: it.month,
                                            selectedDay =
                                                content?.summary?.days?.singleOrNull { day ->
                                                    day.attendance.date ==
                                                        it.selectedDay?.attendance?.date
                                                },
                                            needsVerification =
                                                it.refreshFailure?.code in verificationCodes,
                                        )
                                    }
                                }
                            }
                        }
                    }
            }
        onEvent(AttendanceHistoryEvent.RefreshRequested)
    }

    private fun onStopped(event: AttendanceHistoryEvent.Stopped) {
        observation?.cancel()
        observation = null
        acquisition?.cancel()
        acquisition = null
        updateState {
            it.copy(
                content = null,
                selectedDay = null,
                picker = null,
                loading = false,
                refreshing = false,
            )
        }
    }

    private fun onRefreshRequested(event: AttendanceHistoryEvent.RefreshRequested) {
        if (observation?.isActive != true || acquisition?.isActive == true) return
        val requested = state.value.selection
        updateState {
            it.copy(
                refreshing = true,
                cacheFailure =
                    it.refreshFailure?.takeIf(::calendarFailureInvalidatesCache) ?: it.cacheFailure,
                refreshFailure = it.refreshFailure?.takeIf(::calendarFailureInvalidatesCache),
                needsVerification =
                    it.cacheFailure?.code in verificationCodes ||
                        it.refreshFailure?.code in verificationCodes,
            )
        }
        acquisition =
            viewModelScope.launch {
                val result = refresh(workspaceId, requested)
                ensureActive()
                when (result) {
                    is AppResult.Failed -> {
                        updateState {
                            val invalidated = calendarFailureInvalidatesCache(result.failure)
                            it.copy(
                                selection =
                                    if (
                                        !invalidated &&
                                            it.selection.from == null &&
                                            it.content != null
                                    )
                                        it.selection.copy(
                                            employeeId = it.content.snapshot.employeeId,
                                            from = it.content.snapshot.period.from,
                                        )
                                    else it.selection,
                                refreshing = false,
                                loading = false,
                                refreshFailure = result.failure,
                                content = if (invalidated) null else it.content,
                                selectedDay = if (invalidated) null else it.selectedDay,
                                picker = if (invalidated) null else it.picker,
                                needsVerification =
                                    result.failure.code in verificationCodes ||
                                        it.cacheFailure?.code in verificationCodes,
                            )
                        }
                        if (
                            result.failure.code in workspaceAccessLossCodes &&
                                result.failure.code !in verificationCodes
                        )
                            emitEffect(AttendanceHistoryEffect.RecheckWorkspace)
                    }
                    is AppResult.Success ->
                        updateState {
                            it.copy(
                                refreshing = false,
                                loading = it.content == null,
                                refreshFailure = null,
                                cacheFailure = null,
                                needsVerification = false,
                            )
                        }
                }
            }
    }

    private fun onDateFilterRequested(event: AttendanceHistoryEvent.DateFilterRequested) {
        val month = state.value.month ?: return
        if (observation?.isActive != true) return
        updateState { it.copy(picker = attendanceMonthPicker(month.selected.year, month.today)) }
    }

    private fun onDateFilterDismissed(event: AttendanceHistoryEvent.DateFilterDismissed) {
        updateState { it.copy(picker = null) }
    }

    private fun onPickerYearRequested(event: AttendanceHistoryEvent.PickerYearRequested) {
        val picker = state.value.picker ?: return
        val month = state.value.month ?: return
        if (event.year != picker.previousYear && event.year != picker.nextYear) return
        updateState { it.copy(picker = attendanceMonthPicker(event.year, month.today)) }
    }

    private fun onPickerMonthSelected(event: AttendanceHistoryEvent.PickerMonthSelected) {
        val selected =
            state.value.picker?.months?.singleOrNull {
                it.date.month.number == event.month && it.enabled
            } ?: return
        val month = state.value.month ?: return
        updateState {
            it.copy(month = attendanceMonthFilter(selected.date, month.today), picker = null)
        }
        changeSelection(state.value.selection.copy(from = selected.date))
    }

    private fun onMonthRequested(event: AttendanceHistoryEvent.MonthRequested) {
        val month = state.value.month ?: return
        if (event.from != month.previous && event.from != month.next) return
        updateState { it.copy(month = attendanceMonthFilter(event.from, month.today)) }
        changeSelection(state.value.selection.copy(from = event.from))
    }

    private fun onEmploymentSelected(event: AttendanceHistoryEvent.EmploymentSelected) {
        val snapshot = state.value.content?.snapshot ?: return
        if (snapshot.directory.items.none { it.id == event.id }) return
        changeSelection(
            state.value.selection.copy(employeeId = event.id, from = snapshot.period.from)
        )
    }

    private fun onEmploymentPageRequested(event: AttendanceHistoryEvent.EmploymentPageRequested) {
        val snapshot = state.value.content?.snapshot ?: return
        if (event.after != null && event.after != snapshot.directory.nextCursor) return
        changeSelection(
            AttendanceSelection(
                after = event.after,
                window = AttendanceWindow.MONTHLY,
                from = snapshot.period.from,
            )
        )
    }

    private fun changeSelection(selection: AttendanceSelection) {
        if (observation?.isActive != true) return
        acquisition?.cancel()
        acquisition = null
        updateState {
            it.copy(
                selection = selection,
                content = null,
                selectedDay = null,
                picker = null,
                loading = true,
                refreshing = false,
                cacheFailure = it.cacheFailure?.takeIf(::calendarFailureInvalidatesCache),
                refreshFailure = it.refreshFailure?.takeIf(::calendarFailureInvalidatesCache),
                needsVerification =
                    it.cacheFailure?.code in verificationCodes ||
                        it.refreshFailure?.code in verificationCodes,
            )
        }
        onEvent(AttendanceHistoryEvent.RefreshRequested)
    }

    private fun onDaySelected(event: AttendanceHistoryEvent.DaySelected) {
        val day =
            state.value.content?.summary?.days?.singleOrNull { it.attendance.date == event.date }
                ?: return
        updateState { it.copy(selectedDay = day) }
    }

    private fun onDayDismissed(event: AttendanceHistoryEvent.DayDismissed) {
        updateState { it.copy(selectedDay = null) }
    }

    private fun onBackRequested(event: AttendanceHistoryEvent.BackRequested) {
        viewModelScope.launch { emitEffect(AttendanceHistoryEffect.Back) }
    }

    private fun onVerificationRequested(event: AttendanceHistoryEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(AttendanceHistoryEffect.VerifyAccount) }
    }
}

private val verificationCodes =
    setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
