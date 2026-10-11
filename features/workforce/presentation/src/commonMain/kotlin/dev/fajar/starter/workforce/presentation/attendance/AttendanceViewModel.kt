package dev.fajar.starter.workforce.presentation.attendance

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.location.domain.entities.LocationSettings
import dev.fajar.starter.location.domain.usecases.OpenLocationSettings
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.usecases.*
import dev.fajar.starter.workforce.domain.policies.calendarFailureInvalidatesCache
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class AttendanceViewModel(
    @InjectedParam private val workspaceId: String,
    private val observe: ObserveAttendance,
    private val refresh: RefreshAttendance,
    private val capture: CaptureAttendance,
    private val replay: ReplayAttendance,
    private val dismiss: DismissAttendanceRejection,
    private val settings: OpenLocationSettings,
) : MviViewModel<AttendanceState, AttendanceEvent, AttendanceEffect>(AttendanceState()) {
    private var observation: Job? = null
    private var acquisition: Job? = null
    private var action: Job? = null

    init {
        on<AttendanceEvent.Entered>(::onEntered)
        on<AttendanceEvent.Stopped>(::onStopped)
        on<AttendanceEvent.RefreshRequested>(::onRefreshRequested)
        on<AttendanceEvent.CaptureRequested>(::onCaptureRequested)
        on<AttendanceEvent.RetryRequested>(::onRetryRequested)
        on<AttendanceEvent.RejectionDismissed>(::onRejectionDismissed)
        on<AttendanceEvent.EmploymentSelected>(::onEmploymentSelected)
        on<AttendanceEvent.EmploymentPageRequested>(::onEmploymentPageRequested)
        on<AttendanceEvent.SettingsRequested>(::onSettingsRequested)
        on<AttendanceEvent.VerificationRequested>(::onVerificationRequested)
        on<AttendanceEvent.QueueRequested>(::onQueueRequested)
        on<AttendanceEvent.EmploymentPickerRequested>(::onEmploymentPickerRequested)
        on<AttendanceEvent.DialogDismissed>(::onDialogDismissed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun onEntered(event: AttendanceEvent.Entered) {
        if (observation?.isActive == true) return
        updateState { it.copy(loading = true) }
        observation =
            viewModelScope.launch {
                state
                    .map { it.selection to (it.readFailure != null) }
                    .distinctUntilChanged()
                    .flatMapLatest { (requested, restricted) ->
                        if (restricted) emptyFlow()
                        else observe(workspaceId).map { requested to it }
                    }
                    .collect { (requested, result) ->
                        ensureActive()
                        if (requested != state.value.selection || state.value.readFailure != null)
                            return@collect
                        when (result) {
                            is AppResult.Failed ->
                                updateState {
                                    it.copy(
                                        content = null,
                                        loading = false,
                                        failure = result.failure,
                                        readFailure =
                                            result.failure.takeIf(
                                                ::calendarFailureInvalidatesCache
                                            ),
                                        needsVerification =
                                            result.failure.code in verificationCodes,
                                        dialog = null,
                                        settingsTarget = null,
                                        withoutLocationKind = null,
                                    )
                                }
                            is AppResult.Success -> {
                                val content = result.value.toContent(Clock.System.now())
                                if (
                                    content == null ||
                                        ((state.value.selection.employeeId == null ||
                                            content.employee?.id ==
                                                state.value.selection.employeeId) &&
                                            state.value.selection.after == content.snapshot.after)
                                )
                                    updateState { it.copy(content = content, loading = false) }
                            }
                        }
                    }
            }
        onEvent(AttendanceEvent.RefreshRequested)
    }

    private fun onStopped(event: AttendanceEvent.Stopped) {
        observation?.cancel()
        observation = null
        acquisition?.cancel()
        acquisition = null
        action?.cancel()
        action = null
        updateState {
            it.copy(
                content = null,
                loading = false,
                refreshing = false,
                working = false,
                withoutLocationKind = null,
                dialog = null,
            )
        }
    }

    private fun onRefreshRequested(event: AttendanceEvent.RefreshRequested) {
        if (observation?.isActive != true || acquisition?.isActive == true || state.value.working)
            return
        val selection = state.value.selection
        updateState {
            it.copy(
                refreshing = true,
                failure = it.readFailure,
                needsVerification = it.readFailure?.code in verificationCodes,
                settingsTarget = null,
                withoutLocationKind = null,
            )
        }
        acquisition =
            viewModelScope.launch {
                val result = refresh(workspaceId, selection)
                ensureActive()
                when (result) {
                    is AppResult.Failed -> reportFailure(result.failure)
                    is AppResult.Success ->
                        updateState {
                            it.copy(failure = null, readFailure = null, needsVerification = false)
                        }
                }
                updateState { it.copy(refreshing = false, loading = false) }
            }
    }

    private fun onCaptureRequested(event: AttendanceEvent.CaptureRequested) {
        val content = state.value.content ?: return
        val employee = content.employee ?: return
        if (state.value.working || state.value.refreshing || observation?.isActive != true) return
        if (event.withoutLocation && state.value.withoutLocationKind != event.kind) return
        if (
            event.kind == AttendanceKind.CLOCK_IN && !content.canClockIn ||
                event.kind == AttendanceKind.CLOCK_OUT && !content.canClockOut
        )
            return
        updateState {
            it.copy(
                working = true,
                failure = null,
                settingsTarget = null,
                withoutLocationKind = null,
            )
        }
        action =
            viewModelScope.launch {
                val prepared = capture(workspaceId, employee.id, event.kind, event.withoutLocation)
                ensureActive()
                when (prepared) {
                    is AppResult.Failed -> {
                        reportFailure(prepared.failure)
                        if (prepared.failure.code?.startsWith("client.location_") == true)
                            updateState {
                                it.copy(
                                    withoutLocationKind = event.kind,
                                    settingsTarget =
                                        if (prepared.failure.code == "client.location_disabled")
                                            LocationSettings.Services
                                        else LocationSettings.Application,
                                )
                            }
                    }
                    is AppResult.Success -> {
                        val delivered = replay(workspaceId, prepared.value.command.id)
                        ensureActive()
                        if (delivered is AppResult.Failed) reportFailure(delivered.failure)
                        else
                            (delivered as AppResult.Success).value.outcome.failureOrNull()?.let {
                                reportFailure(it)
                            }
                    }
                }
                updateState { it.copy(working = false) }
                if (prepared is AppResult.Success) onEvent(AttendanceEvent.RefreshRequested)
            }
    }

    private fun onRetryRequested(event: AttendanceEvent.RetryRequested) {
        if (
            state.value.working ||
                observation?.isActive != true ||
                state.value.content?.commands?.none { it.command.id == event.id } != false
        )
            return
        updateState { it.copy(working = true, failure = null) }
        action =
            viewModelScope.launch {
                val result = replay(workspaceId, event.id)
                ensureActive()
                if (result is AppResult.Failed) reportFailure(result.failure)
                else
                    (result as AppResult.Success).value.outcome.failureOrNull()?.let {
                        reportFailure(it)
                    }
                updateState { it.copy(working = false) }
                onEvent(AttendanceEvent.RefreshRequested)
            }
    }

    private fun onRejectionDismissed(event: AttendanceEvent.RejectionDismissed) {
        if (state.value.working || observation?.isActive != true) return
        updateState { it.copy(working = true) }
        action =
            viewModelScope.launch {
                val result = dismiss(workspaceId, event.id, event.revision)
                ensureActive()
                updateState { it.copy(working = false) }
                if (result is AppResult.Failed) reportFailure(result.failure)
            }
    }

    private fun onEmploymentSelected(event: AttendanceEvent.EmploymentSelected) {
        if (
            state.value.working ||
                state.value.content?.snapshot?.directory?.items?.none { it.id == event.id } != false
        )
            return
        acquisition?.cancel()
        acquisition = null
        updateState {
            it.copy(
                selection = it.selection.copy(employeeId = event.id),
                content = null,
                dialog = null,
            )
        }
        onEvent(AttendanceEvent.RefreshRequested)
    }

    private fun onEmploymentPageRequested(event: AttendanceEvent.EmploymentPageRequested) {
        if (
            state.value.working ||
                (event.after != null &&
                    event.after != state.value.content?.snapshot?.directory?.nextCursor)
        )
            return
        acquisition?.cancel()
        acquisition = null
        updateState {
            it.copy(selection = AttendanceSelection(after = event.after), content = null)
        }
        onEvent(AttendanceEvent.RefreshRequested)
    }

    private fun onSettingsRequested(event: AttendanceEvent.SettingsRequested) {
        val target = state.value.settingsTarget ?: return
        if (state.value.working || observation?.isActive != true) return
        updateState { it.copy(working = true) }
        action =
            viewModelScope.launch {
                val result = settings(target)
                ensureActive()
                updateState { it.copy(working = false) }
                if (result is AppResult.Failed) reportFailure(result.failure)
            }
    }

    private fun onVerificationRequested(event: AttendanceEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(AttendanceEffect.VerifyAccount) }
    }

    private fun onQueueRequested(event: AttendanceEvent.QueueRequested) {
        if (observation?.isActive != true || state.value.content?.commands.isNullOrEmpty()) return
        updateState { it.copy(dialog = AttendanceDialog.QUEUE) }
    }

    private fun onEmploymentPickerRequested(event: AttendanceEvent.EmploymentPickerRequested) {
        if (observation?.isActive != true || state.value.content?.hasEmploymentChoice != true)
            return
        updateState { it.copy(dialog = AttendanceDialog.EMPLOYMENT) }
    }

    private fun onDialogDismissed(event: AttendanceEvent.DialogDismissed) {
        updateState { it.copy(dialog = null) }
    }

    private suspend fun reportFailure(failure: Failure) {
        updateState {
            val invalidated = calendarFailureInvalidatesCache(failure)
            it.copy(
                failure = failure,
                readFailure = failure.takeIf { invalidated } ?: it.readFailure,
                needsVerification =
                    failure.code in verificationCodes || it.readFailure?.code in verificationCodes,
                content = if (invalidated || it.readFailure != null) null else it.content,
                dialog = if (invalidated) null else it.dialog,
                settingsTarget = if (invalidated) null else it.settingsTarget,
                withoutLocationKind = if (invalidated) null else it.withoutLocationKind,
            )
        }
        if (failure.code in workspaceAccessLossCodes && failure.code !in verificationCodes)
            emitEffect(AttendanceEffect.RecheckWorkspace)
    }
}

private val verificationCodes =
    setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")

fun AttendanceCommandOutcome.failureOrNull(): Failure? =
    when (this) {
        is AttendanceCommandOutcome.Rejected -> failure
        is AttendanceCommandOutcome.Unconfirmed -> failure
        else -> null
    }
