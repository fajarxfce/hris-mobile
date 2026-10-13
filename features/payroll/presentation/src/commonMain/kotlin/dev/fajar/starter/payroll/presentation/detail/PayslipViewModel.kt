package dev.fajar.starter.payroll.presentation.detail

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.payroll.domain.policies.payslipFailureInvalidatesCache
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
    private val refresh: RefreshPayslip,
    private val export: ExportPayslip,
) : MviViewModel<PayslipState, PayslipEvent, PayslipEffect>(PayslipState()) {
    private var observation: Job? = null
    private var acquisition: Job? = null
    private var exporting: Job? = null

    init {
        on<PayslipEvent.Entered>(::onEntered)
        on<PayslipEvent.Closed>(::onClosed)
        on<PayslipEvent.RefreshRequested>(::onRefreshRequested)
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
        observation =
            viewModelScope.launch {
                state
                    .map { it.readRestricted }
                    .distinctUntilChanged()
                    .flatMapLatest { restricted ->
                        if (restricted) emptyFlow() else observe(target.workspaceId, target.id)
                    }
                    .collect { result ->
                        ensureActive()
                        if (state.value.readRestricted) return@collect
                        val renewExpiredRead =
                            state.value.content != null &&
                                result is AppResult.Failed &&
                                result.failure.code == "client.payslip_cache_expired"
                        updateState { it.withCachedPayslip(result) }
                        if (renewExpiredRead) onEvent(PayslipEvent.RefreshRequested)
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
                loading = false,
                refreshing = false,
                saving = false,
                saved = false,
                exportOptions = false,
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
                saved = false,
                exportOptions = false,
            )
        }
        acquisition =
            viewModelScope.launch {
                val result = refresh(target.workspaceId, target.id)
                ensureActive()
                if (result is AppResult.Success) observation?.cancel()
                updateState { it.withPayslipRefresh(result) }
                if (result is AppResult.Success) observeContent()
                if (
                    result is AppResult.Failed &&
                        result.failure.code in workspaceAccessLossCodes &&
                        result.failure.code !in payslipVerificationCodes
                )
                    emitEffect(PayslipEffect.RecheckWorkspace)
            }
    }

    private fun onExportRequested(event: PayslipEvent.ExportRequested) {
        if (state.value.canSave) updateState { it.copy(exportOptions = true) }
    }

    private fun onExportDismissed(event: PayslipEvent.ExportDismissed) {
        updateState { it.copy(exportOptions = false) }
    }

    private fun onExportLanguageSelected(event: PayslipEvent.ExportLanguageSelected) {
        if (!state.value.canSave || !state.value.exportOptions || exporting?.isActive == true)
            return
        updateState {
            it.copy(exportOptions = false, saving = true, saved = false, exportFailure = null)
        }
        exporting =
            viewModelScope.launch {
                val result = export(target.workspaceId, target.id, event.language)
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
