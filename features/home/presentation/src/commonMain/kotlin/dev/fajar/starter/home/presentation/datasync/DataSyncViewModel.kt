package dev.fajar.starter.home.presentation.datasync

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class DataSyncViewModel(
    @InjectedParam workspace: Workspace,
    observations: List<ObserveCompanyData>,
    synchronizations: List<SyncCompanyData>,
) :
    MviViewModel<DataSyncState, DataSyncEvent, DataSyncEffect>(
        DataSyncState(
            workspace.company.name,
            observations.sortedBy { it.dataSet.ordinal }.map { DataSyncRowState(it.dataSet) },
        )
    ) {
    private val workspaceId = workspace.id
    private val observers = observations.associateBy { it.dataSet }
    private val synchronizers = synchronizations.associateBy { it.dataSet }
    private val readSlots = observers.mapValues { Mutex() }
    private val reads = mutableMapOf<CompanyDataSet, Job>()
    private val refreshes = mutableMapOf<CompanyDataSet, Job>()
    private val acquisitionSlots = Semaphore(3)
    private var visible = false

    init {
        require(
            observers.size == observations.size && synchronizers.size == synchronizations.size
        ) {
            "Each company dataset must have one observation and one sync binding."
        }
        require(observers.keys == synchronizers.keys) {
            "Company data observation and sync bindings must cover the same datasets."
        }
        on<DataSyncEvent.Entered>(::onEntered)
        on<DataSyncEvent.Stopped>(::onStopped)
        on<DataSyncEvent.RefreshRequested>(::onRefreshRequested)
        on<DataSyncEvent.DatasetRefreshRequested>(::onDatasetRefreshRequested)
        on<DataSyncEvent.CancelRequested>(::onCancelRequested)
        on<DataSyncEvent.VerificationRequested>(::onVerificationRequested)
        on<DataSyncEvent.BackRequested>(::onBackRequested)
    }

    private fun onEntered(event: DataSyncEvent.Entered) {
        if (visible) return
        visible = true
        observers.keys.forEach { dataSet ->
            if (reads[dataSet]?.isActive != true) observeData(dataSet)
        }
    }

    private fun onStopped(event: DataSyncEvent.Stopped) {
        visible = false
        refreshes.values.toList().forEach { it.cancel() }
    }

    private fun onRefreshRequested(event: DataSyncEvent.RefreshRequested) {
        if (!visible || state.value.syncing) return
        observers.keys.forEach(::refresh)
    }

    private fun onDatasetRefreshRequested(event: DataSyncEvent.DatasetRefreshRequested) {
        refresh(event.dataSet)
    }

    private fun onCancelRequested(event: DataSyncEvent.CancelRequested) {
        refreshes.values.toList().forEach { it.cancel() }
    }

    private fun observeData(dataSet: CompanyDataSet) {
        reads[dataSet]?.cancel()
        reads[dataSet] =
            viewModelScope.launch {
                readSlots.getValue(dataSet).withLock {
                    observers.getValue(dataSet).observeStatus(workspaceId).collect { result ->
                        ensureActive()
                        updateRow(dataSet) { it.withDataStatus(result) }
                    }
                }
            }
    }

    private fun refresh(dataSet: CompanyDataSet) {
        if (!visible || dataSet in refreshes) return
        val synchronize = synchronizers[dataSet] ?: return
        updateRow(dataSet) { it.copy(progress = DataSyncProgress.Queued, syncFailure = null) }
        // Reopen failed storage observation without withdrawing its previous read restriction.
        observeData(dataSet)
        val request =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                val owner = coroutineContext.job
                try {
                    val result =
                        acquisitionSlots.withPermit {
                            ensureActive()
                            updateRow(dataSet) { it.copy(progress = DataSyncProgress.Running) }
                            synchronize.refresh(workspaceId)
                        }
                    ensureActive()
                    updateRow(dataSet) { it.withDataSyncResult(result) }
                    if (result is AppResult.Success) observeData(dataSet)
                } finally {
                    if (refreshes[dataSet] === owner) {
                        refreshes.remove(dataSet)
                        updateRow(dataSet) {
                            if (it.syncing) it.copy(progress = DataSyncProgress.Idle) else it
                        }
                    }
                }
            }
        refreshes[dataSet] = request
        request.start()
    }

    private fun updateRow(
        dataSet: CompanyDataSet,
        reducer: (DataSyncRowState) -> DataSyncRowState,
    ) {
        updateState { current ->
            current.copy(rows = current.rows.map { if (it.dataSet == dataSet) reducer(it) else it })
        }
    }

    private fun onVerificationRequested(event: DataSyncEvent.VerificationRequested) {
        if (!visible || !state.value.needsVerification) return
        viewModelScope.launch { emitEffect(DataSyncEffect.VerifyAccount) }
    }

    private fun onBackRequested(event: DataSyncEvent.BackRequested) {
        if (!visible) return
        visible = false
        refreshes.values.toList().forEach { it.cancel() }
        viewModelScope.launch { emitEffect(DataSyncEffect.Back) }
    }
}
