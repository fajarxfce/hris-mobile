package dev.fajar.starter.documents.presentation.catalog

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.replica.usecases.ObserveDocumentCatalog
import dev.fajar.starter.documents.domain.replica.usecases.SyncDocumentCatalog
import dev.fajar.starter.documents.domain.usecases.PrepareDocumentDownload
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.usecases.RefreshWorkspace
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class DocumentCatalogViewModel(
    @InjectedParam private val workspaceId: String,
    private val observe: ObserveDocumentCatalog,
    private val sync: SyncDocumentCatalog,
    private val prepareDownload: PrepareDocumentDownload,
    private val refreshWorkspace: RefreshWorkspace,
) :
    MviViewModel<DocumentCatalogState, DocumentCatalogEvent, DocumentCatalogEffect>(
        DocumentCatalogState()
    ) {
    private var opened = false
    private var observation: Job? = null
    private var acquisition: Job? = null
    private var download: Job? = null
    private var refreshRequired = true

    init {
        on<DocumentCatalogEvent.Opened>(::onOpened)
        on<DocumentCatalogEvent.Closed>(::onClosed)
        on<DocumentCatalogEvent.RefreshRequested>(::onRefreshRequested)
        on<DocumentCatalogEvent.FilesRequested>(::onFilesRequested)
        on<DocumentCatalogEvent.EmploymentRequested>(::onEmploymentRequested)
        on<DocumentCatalogEvent.EmploymentDismissed>(::onEmploymentDismissed)
        on<DocumentCatalogEvent.EmploymentSelected>(::onEmploymentSelected)
        on<DocumentCatalogEvent.ClassificationChanged>(::onClassificationChanged)
        on<DocumentCatalogEvent.PageRequested>(::onPageRequested)
        on<DocumentCatalogEvent.DownloadRequested>(::onDownloadRequested)
        on<DocumentCatalogEvent.DetailsRequested>(::onDetailsRequested)
        on<DocumentCatalogEvent.VerificationRequested>(::onVerificationRequested)
        on<DocumentCatalogEvent.BackRequested>(::onBackRequested)
    }

    private fun onOpened(event: DocumentCatalogEvent.Opened) {
        if (opened) return
        opened = true
        if (observation?.isActive != true) observeSelection()
        if (refreshRequired) acquire(force = state.value.catalog != null)
    }

    private fun onClosed(event: DocumentCatalogEvent.Closed) {
        opened = false
        acquisition?.cancel()
        acquisition = null
        download?.cancel()
        download = null
        updateState {
            it.copy(
                loading = false,
                syncing = false,
                preparingId = null,
                choosingEmployment = false,
            )
        }
    }

    private fun observeSelection() {
        observation?.cancel()
        // The route retains ownership and admission observation while another page is above it.
        observation =
            viewModelScope.launch {
                observe(workspaceId, state.map { it.selection }.distinctUntilChanged()).collect {
                    result ->
                    ensureActive()
                    updateState { it.withCatalog(result) }
                    if (state.value.restriction != null || state.value.preparingId == null)
                        download?.cancel()
                }
            }
    }

    private fun onRefreshRequested(event: DocumentCatalogEvent.RefreshRequested) {
        acquire(force = true)
    }

    private fun acquire(force: Boolean) {
        if (!opened || acquisition?.isActive == true || state.value.preparingId != null) return
        refreshRequired = true
        updateState { it.copy(syncing = true, failure = null) }
        acquisition =
            viewModelScope.launch {
                if (state.value.restriction != null) {
                    val refreshed = refreshWorkspace()
                    ensureActive()
                    when (refreshed) {
                        is SyncResult.Blocked -> {
                            updateState {
                                it.withCatalogFailure(refreshed.failure).copy(syncing = false)
                            }
                            return@launch
                        }
                        is SyncResult.Retry -> {
                            updateState {
                                it.copy(
                                    syncing = false,
                                    loading = false,
                                    failure = refreshed.failure,
                                )
                            }
                            return@launch
                        }
                        SyncResult.Complete -> Unit
                    }
                }
                val result = sync(workspaceId, force)
                ensureActive()
                val caughtUp = result is AppResult.Success && result.value.caughtUp
                refreshRequired = result is AppResult.Success && !caughtUp
                when (result) {
                    is AppResult.Failed ->
                        updateState { it.withCatalogFailure(result.failure).copy(syncing = false) }
                    is AppResult.Success ->
                        updateState { it.copy(syncing = false, loading = false) }
                }
                // Reopen a terminated storage source only after admitted acquisition succeeds.
                if (caughtUp) observeSelection()
            }
    }

    private fun onFilesRequested(event: DocumentCatalogEvent.FilesRequested) {
        if (!opened || state.value.preparingId != null || state.value.choosingEmployment) return
        onClosed(DocumentCatalogEvent.Closed)
        viewModelScope.launch { emitEffect(DocumentCatalogEffect.OpenFiles) }
    }

    private fun onEmploymentRequested(event: DocumentCatalogEvent.EmploymentRequested) {
        if (opened && state.value.canChoose) updateState { it.copy(choosingEmployment = true) }
    }

    private fun onEmploymentDismissed(event: DocumentCatalogEvent.EmploymentDismissed) {
        updateState { it.copy(choosingEmployment = false) }
    }

    private fun onEmploymentSelected(event: DocumentCatalogEvent.EmploymentSelected) {
        val current = state.value
        if (!opened || !current.choosingEmployment || !current.canChoose) return
        if (current.employments?.none { it.id == event.id } != false) return
        if (current.selection.employmentId == event.id) {
            updateState { it.copy(choosingEmployment = false) }
            return
        }
        updateState {
            it.copy(
                selection = it.selection.copy(employmentId = event.id, after = null),
                choosingEmployment = false,
                catalog = null,
                loading = true,
            )
        }
        viewModelScope.launch { emitEffect(DocumentCatalogEffect.ScrollToStart) }
    }

    private fun onClassificationChanged(event: DocumentCatalogEvent.ClassificationChanged) {
        val current = state.value
        if (!opened || !current.canChoose || event.value == current.selection.classification) return
        updateState {
            it.copy(
                selection = it.selection.copy(classification = event.value, after = null),
                catalog = null,
                loading = true,
            )
        }
        viewModelScope.launch { emitEffect(DocumentCatalogEffect.ScrollToStart) }
    }

    private fun onPageRequested(event: DocumentCatalogEvent.PageRequested) {
        val current = state.value
        if (
            !opened ||
                !current.canChoose ||
                current.choosingEmployment ||
                event.after == current.selection.after ||
                (event.after != null && event.after != current.catalog?.page?.nextCursor)
        )
            return
        updateState {
            it.copy(
                selection = it.selection.copy(after = event.after),
                catalog = null,
                loading = true,
            )
        }
        viewModelScope.launch { emitEffect(DocumentCatalogEffect.ScrollToStart) }
    }

    private fun onDownloadRequested(event: DocumentCatalogEvent.DownloadRequested) {
        val current = state.value
        val catalog = current.catalog ?: return
        if (
            !opened ||
                !current.canChoose ||
                current.choosingEmployment ||
                catalog.page.items.none { it.evidence.revisionId == event.revisionId }
        )
            return
        updateState { it.copy(preparingId = event.revisionId, failure = null) }
        download =
            viewModelScope.launch {
                val result = prepareDownload(workspaceId, catalog, event.revisionId)
                ensureActive()
                updateState { it.copy(preparingId = null) }
                when (result) {
                    // Content authorization is separate from catalog admission.
                    is AppResult.Failed -> updateState { it.copy(failure = result.failure) }
                    is AppResult.Success ->
                        emitEffect(DocumentCatalogEffect.OpenDownload(result.value.draft.id))
                }
            }
    }

    private fun onVerificationRequested(event: DocumentCatalogEvent.VerificationRequested) {
        if (opened && state.value.needsVerification)
            viewModelScope.launch { emitEffect(DocumentCatalogEffect.VerifyAccount) }
    }

    private fun onDetailsRequested(event: DocumentCatalogEvent.DetailsRequested) {
        val current = state.value
        val available =
            current.catalog?.let { snapshot ->
                snapshot.page.items.any { it.id == event.documentId } ||
                    snapshot.restrictions.any { it.documentId == event.documentId }
            } == true
        if (!opened || !current.canChoose || current.choosingEmployment || !available) return
        onClosed(DocumentCatalogEvent.Closed)
        viewModelScope.launch { emitEffect(DocumentCatalogEffect.OpenDetails(event.documentId)) }
    }

    private fun onBackRequested(event: DocumentCatalogEvent.BackRequested) {
        if (!opened) return
        onClosed(DocumentCatalogEvent.Closed)
        viewModelScope.launch { emitEffect(DocumentCatalogEffect.Back) }
    }
}
