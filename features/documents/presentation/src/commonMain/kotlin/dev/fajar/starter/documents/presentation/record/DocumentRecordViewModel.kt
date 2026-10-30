package dev.fajar.starter.documents.presentation.record

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.metadata.usecases.*
import dev.fajar.starter.documents.domain.replica.policies.documentCatalogFailureClosesRead
import dev.fajar.starter.documents.domain.replica.usecases.SyncDocumentCatalog
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.usecases.RefreshWorkspace
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class DocumentRecordViewModel(
    @InjectedParam private val target: DocumentRecordTarget,
    private val observeMetadata: ObserveDocumentMetadata,
    private val refreshMetadata: RefreshDocumentMetadata,
    private val syncCatalog: SyncDocumentCatalog,
    private val refreshWorkspace: RefreshWorkspace,
) :
    MviViewModel<DocumentRecordState, DocumentRecordEvent, DocumentRecordEffect>(
        DocumentRecordState()
    ) {
    private var opened = false
    private var generation = 0
    private var observation: Job? = null
    private var acquisition: Job? = null
    private val selected = MutableStateFlow<Long?>(null)

    init {
        on<DocumentRecordEvent.Opened>(::onOpened)
        on<DocumentRecordEvent.Closed>(::onClosed)
        on<DocumentRecordEvent.RefreshRequested>(::onRefreshRequested)
        on<DocumentRecordEvent.HistoryRequested>(::onHistoryRequested)
        on<DocumentRecordEvent.VerificationRequested>(::onVerificationRequested)
        on<DocumentRecordEvent.BackRequested>(::onBackRequested)
        on<DocumentRecordEvent.EditRequested>(::onEditRequested)
    }

    private fun onOpened(event: DocumentRecordEvent.Opened) {
        if (opened) return
        opened = true
        observe()
        acquire(false)
    }

    private fun onClosed(event: DocumentRecordEvent.Closed) {
        opened = false
        generation++
        observation?.cancel()
        observation = null
        acquisition?.cancel()
        acquisition = null
        updateState { it.copy(view = null, loading = false, refreshing = false) }
    }

    private fun observe() {
        observation?.cancel()
        updateState { it.copy(loading = it.view == null) }
        observation =
            viewModelScope.launch {
                observeMetadata(target.workspaceId, target.documentId, selected).collect { result ->
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> report(result.failure)
                        is AppResult.Success ->
                            updateState {
                                it.copy(view = result.value, loading = false, restriction = null)
                            }
                    }
                }
            }
    }

    private fun onRefreshRequested(event: DocumentRecordEvent.RefreshRequested) = acquire(true)

    private fun onHistoryRequested(event: DocumentRecordEvent.HistoryRequested) {
        if (!opened || !state.value.canPage) return
        val snapshot = state.value.view?.snapshot ?: return
        if (event.after != null && event.after != snapshot.history.nextCursor) return
        selected.value = event.after
        updateState { it.copy(after = event.after, failure = null) }
        viewModelScope.launch { emitEffect(DocumentRecordEffect.ScrollToStart) }
        acquire(false)
    }

    private fun acquire(force: Boolean) {
        if (!opened || acquisition?.isActive == true) return
        val owner = ++generation
        updateState { it.copy(refreshing = true, failure = null) }
        acquisition =
            viewModelScope.launch {
                try {
                    if (state.value.restriction != null && !revalidateWorkspace()) return@launch
                    val synchronized = syncCatalog(target.workspaceId, force)
                    ensureActive()
                    if (synchronized is AppResult.Failed) {
                        report(synchronized.failure)
                        return@launch
                    }
                    val result =
                        refreshMetadata(target.workspaceId, target.documentId, selected.value)
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> {
                            report(result.failure)
                            emitEffect(DocumentRecordEffect.ScrollToStart)
                        }
                        is AppResult.Success -> {
                            updateState { it.copy(failure = null) }
                            // A source that ended with a storage error needs a new subscription.
                            observe()
                        }
                    }
                } finally {
                    if (opened && generation == owner)
                        updateState { it.copy(refreshing = false, loading = false) }
                }
            }
    }

    private suspend fun revalidateWorkspace(): Boolean {
        val result = refreshWorkspace()
        currentCoroutineContext().ensureActive()
        return when (result) {
            is SyncResult.Blocked -> {
                report(result.failure)
                false
            }
            is SyncResult.Retry -> {
                result.failure?.let(::report)
                false
            }
            SyncResult.Complete -> true
        }
    }

    private fun report(failure: Failure) {
        val denied =
            documentCatalogFailureClosesRead(failure) || failure.code == "document_not_found"
        updateState {
            val restriction = if (denied) failure else it.restriction
            it.copy(
                view = if (restriction != null) null else it.view,
                loading = false,
                failure = failure,
                restriction = restriction,
            )
        }
    }

    private fun onVerificationRequested(event: DocumentRecordEvent.VerificationRequested) {
        if (opened && state.value.needsVerification)
            viewModelScope.launch { emitEffect(DocumentRecordEffect.VerifyAccount) }
    }

    private fun onBackRequested(event: DocumentRecordEvent.BackRequested) {
        if (!opened) return
        onClosed(DocumentRecordEvent.Closed)
        viewModelScope.launch { emitEffect(DocumentRecordEffect.Back) }
    }

    private fun onEditRequested(event: DocumentRecordEvent.EditRequested) {
        if (!opened || !state.value.canEdit) return
        onClosed(DocumentRecordEvent.Closed)
        viewModelScope.launch { emitEffect(DocumentRecordEffect.Edit(target.documentId)) }
    }
}
