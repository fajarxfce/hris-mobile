package dev.fajar.starter.payroll.presentation.detail

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.payroll.domain.policies.payslipFailureInvalidatesCache
import dev.fajar.starter.payroll.domain.replica.usecases.SyncPayroll
import dev.fajar.starter.payroll.domain.usecases.*
import dev.fajar.starter.payroll.presentation.payslipVerificationCodes
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class PayslipViewModel(
    @InjectedParam private val target: PayslipTarget,
    private val observe: ObservePayslip,
    private val sync: SyncPayroll,
    private val loadRevision: LoadPayslipRevision,
    private val export: ExportPayslip,
) : MviViewModel<PayslipState, PayslipEvent, PayslipEffect>(PayslipState()) {
    private var observation: Job? = null
    private var acquisition: Job? = null
    private var exporting: Job? = null

    init {
        on<PayslipEvent.Entered>(::onEntered)
        on<PayslipEvent.Closed>(::onClosed)
        on<PayslipEvent.RefreshRequested>(::onRefreshRequested)
        on<PayslipEvent.PreviousRevisionRequested>(::onPreviousRevisionRequested)
        on<PayslipEvent.NextRevisionRequested>(::onNextRevisionRequested)
        on<PayslipEvent.CurrentRevisionRequested>(::onCurrentRevisionRequested)
        on<PayslipEvent.ExportRequested>(::onExportRequested)
        on<PayslipEvent.ExportDismissed>(::onExportDismissed)
        on<PayslipEvent.ExportLanguageSelected>(::onExportLanguageSelected)
        on<PayslipEvent.BackRequested>(::onBackRequested)
        on<PayslipEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onEntered(event: PayslipEvent.Entered) {
        if (observation?.isActive == true) return
        updateState { it.copy(loading = true) }
        observeContent()
        onEvent(PayslipEvent.RefreshRequested)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeContent() {
        observation?.cancel()
        val revision = state.value.selectedRevision
        observation =
            viewModelScope.launch {
                state
                    .map { it.readRestricted }
                    .distinctUntilChanged()
                    .flatMapLatest { restricted ->
                        if (restricted) emptyFlow()
                        else observe(target.workspaceId, target.id, revision)
                    }
                    .collect { result ->
                        ensureActive()
                        if (state.value.readRestricted || state.value.selectedRevision != revision)
                            return@collect
                        updateState { it.withCachedPayslip(result) }
                    }
            }
    }

    private fun onClosed(event: PayslipEvent.Closed) {
        observation?.cancel()
        observation = null
        acquisition?.cancel()
        acquisition = null
        exporting?.cancel()
        exporting = null
        updateState {
            it.copy(
                content = null,
                currentRevision = null,
                revisionLoading = false,
                loading = false,
                refreshing = false,
                saving = false,
                saved = false,
                exportRevision = null,
            )
        }
    }

    private fun onRefreshRequested(event: PayslipEvent.RefreshRequested) {
        if (
            observation?.isActive != true ||
                acquisition?.isActive == true ||
                exporting?.isActive == true
        )
            return
        updateState {
            it.copy(
                refreshing = true,
                loading = it.content == null,
                cacheFailure = it.restriction ?: it.cacheFailure,
                refreshFailure = it.refreshFailure?.takeIf(::payslipFailureInvalidatesCache),
                exportFailure = it.exportFailure?.takeIf(::payslipFailureInvalidatesCache),
                revisionFailure = it.revisionFailure?.takeIf(::payslipFailureInvalidatesCache),
                saved = false,
                exportRevision = null,
            )
        }
        acquisition =
            viewModelScope.launch {
                val result = sync(target.workspaceId)
                ensureActive()
                val caughtUp = result is AppResult.Success && result.value.caughtUp
                if (caughtUp) observation?.cancel()
                updateState { it.withPayslipRefresh(result) }
                if (caughtUp) observeContent()
                val revision = state.value.selectedRevision
                if (caughtUp && revision != null && !state.value.readRestricted) {
                    updateState { it.copy(revisionLoading = true) }
                    val selected = loadRevision(target.workspaceId, target.id, revision)
                    ensureActive()
                    updateState { it.withRevisionLoad(selected) }
                    if (selected is AppResult.Success) observeContent()
                    recheckWorkspace(selected)
                }
                if (
                    result is AppResult.Failed &&
                        result.failure.code in workspaceAccessLossCodes &&
                        result.failure.code !in payslipVerificationCodes
                )
                    emitEffect(PayslipEffect.RecheckWorkspace)
            }
    }

    private fun onPreviousRevisionRequested(event: PayslipEvent.PreviousRevisionRequested) {
        if (state.value.canChoosePrevious)
            selectRevision(requireNotNull(state.value.displayedRevision) - 1)
    }

    private fun onNextRevisionRequested(event: PayslipEvent.NextRevisionRequested) {
        if (state.value.canChooseNext) {
            val next = requireNotNull(state.value.displayedRevision) + 1
            selectRevision(next.takeUnless { it == state.value.currentRevision })
        }
    }

    private fun onCurrentRevisionRequested(event: PayslipEvent.CurrentRevisionRequested) {
        if (state.value.canChooseRevision && state.value.selectedRevision != null)
            selectRevision(null)
    }

    private fun selectRevision(revision: Long?) {
        acquisition?.cancel()
        acquisition = null
        observation?.cancel()
        updateState {
            it.copy(
                selectedRevision = revision,
                content = null,
                loading = true,
                revisionLoading = revision != null,
                saved = false,
                exportRevision = null,
                cacheFailure = it.restriction,
                refreshFailure = null,
                exportFailure = null,
                revisionFailure = null,
            )
        }
        observeContent()
        if (revision == null) return
        acquisition =
            viewModelScope.launch {
                val result = loadRevision(target.workspaceId, target.id, revision)
                ensureActive()
                updateState { it.withRevisionLoad(result) }
                if (result is AppResult.Success) observeContent()
                recheckWorkspace(result)
            }
    }

    private suspend fun recheckWorkspace(result: AppResult<*>) {
        if (
            result is AppResult.Failed &&
                result.failure.code in workspaceAccessLossCodes &&
                result.failure.code !in payslipVerificationCodes
        )
            emitEffect(PayslipEffect.RecheckWorkspace)
    }

    private fun onExportRequested(event: PayslipEvent.ExportRequested) {
        if (state.value.canSave && state.value.exportRevision == null)
            updateState { it.copy(exportRevision = it.content?.value?.payslip?.summary?.version) }
    }

    private fun onExportDismissed(event: PayslipEvent.ExportDismissed) {
        updateState { it.copy(exportRevision = null) }
    }

    private fun onExportLanguageSelected(event: PayslipEvent.ExportLanguageSelected) {
        val revision = state.value.exportRevision ?: return
        if (!state.value.canSave || exporting?.isActive == true) return
        updateState {
            it.copy(exportRevision = null, saving = true, saved = false, exportFailure = null)
        }
        exporting =
            viewModelScope.launch {
                val result = export(target.workspaceId, target.id, revision, event.language)
                ensureActive()
                updateState { it.withPayslipExport(result) }
                if (
                    result is AppResult.Failed &&
                        result.failure.code in workspaceAccessLossCodes &&
                        result.failure.code !in payslipVerificationCodes
                )
                    emitEffect(PayslipEffect.RecheckWorkspace)
            }
    }

    private fun onBackRequested(event: PayslipEvent.BackRequested) {
        viewModelScope.launch { emitEffect(PayslipEffect.Back) }
    }

    private fun onVerificationRequested(event: PayslipEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(PayslipEffect.VerifyAccount) }
    }
}
