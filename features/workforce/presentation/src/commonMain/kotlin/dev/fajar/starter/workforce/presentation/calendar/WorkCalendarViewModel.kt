package dev.fajar.starter.workforce.presentation.calendar

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workforce.domain.entities.WorkCalendarSelection
import dev.fajar.starter.workforce.domain.policies.calendarFailureInvalidatesCache
import dev.fajar.starter.workforce.domain.usecases.*
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class WorkCalendarViewModel(
    @InjectedParam private val workspaceId: String,
    private val observe: ObserveWorkCalendar,
    private val refresh: RefreshWorkCalendar,
) : MviViewModel<WorkCalendarState, WorkCalendarEvent, WorkCalendarEffect>(WorkCalendarState()) {
    private var observation: Job? = null
    private var acquisition: Job? = null

    init {
        on<WorkCalendarEvent.Entered>(::onEntered)
        on<WorkCalendarEvent.Stopped>(::onStopped)
        on<WorkCalendarEvent.RefreshRequested>(::onRefreshRequested)
        on<WorkCalendarEvent.CurrentWeekRequested>(::onCurrentWeekRequested)
        on<WorkCalendarEvent.WeekRequested>(::onWeekRequested)
        on<WorkCalendarEvent.EmploymentSelected>(::onEmploymentSelected)
        on<WorkCalendarEvent.EmploymentPageRequested>(::onEmploymentPageRequested)
        on<WorkCalendarEvent.BackRequested>(::onBackRequested)
        on<WorkCalendarEvent.VerificationRequested>(::onVerificationRequested)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun onEntered(event: WorkCalendarEvent.Entered) {
        if (observation?.isActive == true) return
        updateState { it.copy(loading = true) }
        observation =
            viewModelScope.launch {
                state
                    .map { it.selection to it.readRestricted }
                    .distinctUntilChanged()
                    .flatMapLatest { (requested, restricted) ->
                        if (restricted) emptyFlow()
                        else observe(workspaceId).map { requested to it }
                    }
                    .collect { (requested, result) ->
                        ensureActive()
                        if (requested != state.value.selection || state.value.readRestricted)
                            return@collect
                        when (result) {
                            is AppResult.Failed ->
                                updateState {
                                    it.copy(
                                        content = null,
                                        loading = false,
                                        cacheFailure = result.failure,
                                        needsVerification =
                                            result.failure.code in calendarVerificationCodes ||
                                                it.refreshFailure?.code in calendarVerificationCodes,
                                    )
                                }
                            is AppResult.Success -> {
                                val snapshot = result.value
                                val matches =
                                    snapshot == null ||
                                        requested == WorkCalendarSelection() ||
                                        ((requested.employeeId == null ||
                                            requested.employeeId ==
                                                snapshot.selection.employeeId) &&
                                            (requested.from == null ||
                                                requested.from == snapshot.period.from) &&
                                            requested.after == snapshot.selection.after)
                                if (matches)
                                    updateState {
                                        it.copy(
                                            content = snapshot?.toContent(),
                                            loading = false,
                                            cacheFailure = null,
                                            selection =
                                                if (
                                                    !it.refreshing &&
                                                        it.refreshFailure != null &&
                                                        requested.from == null &&
                                                        snapshot != null
                                                )
                                                    snapshot.selection
                                                else it.selection,
                                            needsVerification =
                                                it.refreshFailure?.code in calendarVerificationCodes,
                                        )
                                    }
                            }
                        }
                    }
            }
        onEvent(WorkCalendarEvent.RefreshRequested)
    }

    private fun onStopped(event: WorkCalendarEvent.Stopped) {
        observation?.cancel()
        observation = null
        acquisition?.cancel()
        acquisition = null
        updateState {
            it.copy(
                selection = it.content?.snapshot?.selection ?: it.selection,
                content = null,
                loading = false,
                refreshing = false,
            )
        }
    }

    private fun onRefreshRequested(event: WorkCalendarEvent.RefreshRequested) {
        if (observation?.isActive != true || acquisition?.isActive == true) return
        val requested = state.value.selection
        updateState {
            it.copy(
                refreshing = true,
                cacheFailure =
                    it.refreshFailure?.takeIf(::calendarFailureInvalidatesCache) ?: it.cacheFailure,
                refreshFailure = it.refreshFailure?.takeIf(::calendarFailureInvalidatesCache),
                needsVerification =
                    it.cacheFailure?.code in calendarVerificationCodes ||
                        it.refreshFailure?.code in calendarVerificationCodes,
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
                                refreshing = false,
                                loading = false,
                                refreshFailure = result.failure,
                                content = if (invalidated) null else it.content,
                                selection =
                                    if (
                                        !invalidated &&
                                            it.selection.from == null &&
                                            it.content != null
                                    )
                                        it.content.snapshot.selection
                                    else it.selection,
                                needsVerification =
                                    result.failure.code in calendarVerificationCodes ||
                                        it.cacheFailure?.code in calendarVerificationCodes,
                            )
                        }
                        if (
                            result.failure.code in workspaceAccessLossCodes &&
                                result.failure.code !in calendarVerificationCodes
                        )
                            emitEffect(WorkCalendarEffect.RecheckWorkspace)
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

    private fun onWeekRequested(event: WorkCalendarEvent.WeekRequested) {
        val content = state.value.content ?: return
        if (event.from != content.previousWeek && event.from != content.nextWeek) return
        acquisition?.cancel()
        acquisition = null
        updateState { it.copy(selection = content.snapshot.selection.copy(from = event.from)) }
        onEvent(WorkCalendarEvent.RefreshRequested)
    }

    private fun onCurrentWeekRequested(event: WorkCalendarEvent.CurrentWeekRequested) {
        acquisition?.cancel()
        acquisition = null
        updateState { it.copy(selection = it.selection.copy(from = null)) }
        onEvent(WorkCalendarEvent.RefreshRequested)
    }

    private fun onEmploymentSelected(event: WorkCalendarEvent.EmploymentSelected) {
        val snapshot = state.value.content?.snapshot ?: return
        if (snapshot.directory.items.none { it.id == event.id }) return
        acquisition?.cancel()
        acquisition = null
        updateState { it.copy(selection = snapshot.selection.copy(employeeId = event.id)) }
        onEvent(WorkCalendarEvent.RefreshRequested)
    }

    private fun onEmploymentPageRequested(event: WorkCalendarEvent.EmploymentPageRequested) {
        val snapshot = state.value.content?.snapshot ?: return
        if (event.after != null && event.after != snapshot.directory.nextCursor) return
        acquisition?.cancel()
        acquisition = null
        updateState {
            it.copy(
                selection = WorkCalendarSelection(from = snapshot.period.from, after = event.after)
            )
        }
        onEvent(WorkCalendarEvent.RefreshRequested)
    }

    private fun onBackRequested(event: WorkCalendarEvent.BackRequested) {
        viewModelScope.launch { emitEffect(WorkCalendarEffect.Back) }
    }

    private fun onVerificationRequested(event: WorkCalendarEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(WorkCalendarEffect.VerifyAccount) }
    }
}

private val calendarVerificationCodes =
    setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
