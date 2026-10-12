package dev.fajar.starter.documents.presentation.catalog

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.usecases.*
import dev.fajar.starter.documents.presentation.access.documentReadRestricted
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.usecases.RefreshWorkspace
import kotlin.time.Clock
import kotlinx.coroutines.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class DocumentCatalogViewModel(
    @InjectedParam private val workspaceId: String,
    private val loadEmployments: LoadDocumentEmployments,
    private val loadEvidence: LoadDocumentEvidence,
    private val prepareDownload: PrepareDocumentDownload,
    private val observeAccess: ObserveDocumentAccess,
    private val refreshWorkspace: RefreshWorkspace,
) :
    MviViewModel<DocumentCatalogState, DocumentCatalogEvent, DocumentCatalogEffect>(
        DocumentCatalogState()
    ) {
    private var opened = false
    private var request: Job? = null
    private var admission: Job? = null
    private var expiry: Job? = null

    init {
        on<DocumentCatalogEvent.Opened>(::onOpened)
        on<DocumentCatalogEvent.Closed>(::onClosed)
        on<DocumentCatalogEvent.RefreshRequested>(::onRefreshRequested)
        on<DocumentCatalogEvent.EmploymentRequested>(::onEmploymentRequested)
        on<DocumentCatalogEvent.EmploymentDismissed>(::onEmploymentDismissed)
        on<DocumentCatalogEvent.EmploymentPageRequested>(::onEmploymentPageRequested)
        on<DocumentCatalogEvent.EmploymentSelected>(::onEmploymentSelected)
        on<DocumentCatalogEvent.ClassificationChanged>(::onClassificationChanged)
        on<DocumentCatalogEvent.PageRequested>(::onPageRequested)
        on<DocumentCatalogEvent.DownloadRequested>(::onDownloadRequested)
        on<DocumentCatalogEvent.CatalogExpired>(::onCatalogExpired)
        on<DocumentCatalogEvent.VerificationRequested>(::onVerificationRequested)
        on<DocumentCatalogEvent.BackRequested>(::onBackRequested)
    }

    private fun onOpened(event: DocumentCatalogEvent.Opened) {
        if (opened) return
        opened = true
        if (state.value.restriction != null) return
        observeAdmission()
        requestDirectory(null, initial = true)
    }

    private fun onClosed(event: DocumentCatalogEvent.Closed) {
        opened = false
        request?.cancel()
        request = null
        admission?.cancel()
        admission = null
        expiry?.cancel()
        expiry = null
        updateState {
            DocumentCatalogState(
                classification = it.classification,
                loading = false,
                restriction = it.restriction,
            )
        }
    }

    private fun observeAdmission() {
        admission?.cancel()
        admission =
            viewModelScope.launch {
                observeAccess(workspaceId).collect { result ->
                    ensureActive()
                    if (result is AppResult.Failed) {
                        request?.cancel()
                        expiry?.cancel()
                        updateState {
                            it.copy(
                                directory = null,
                                employment = null,
                                catalog = null,
                                loading = false,
                                preparingId = null,
                            )
                        }
                        reportFailure(result.failure)
                    }
                }
            }
    }

    private fun onRefreshRequested(event: DocumentCatalogEvent.RefreshRequested) {
        if (!opened || state.value.busy) return
        if (state.value.restriction == null) {
            val employment = state.value.employment
            if (employment == null) requestDirectory(null, initial = true) else requestPage(null)
            return
        }
        updateState { it.copy(loading = true, failure = null) }
        request =
            viewModelScope.launch {
                when (val refreshed = refreshWorkspace()) {
                    SyncResult.Complete -> {
                        ensureActive()
                        acquireDirectory(null, initial = true, revalidated = true)
                    }
                    is SyncResult.Blocked -> {
                        ensureActive()
                        reportFailure(refreshed.failure)
                    }
                    is SyncResult.Retry -> {
                        ensureActive()
                        updateState { it.copy(loading = false) }
                        refreshed.failure?.let(::reportFailure)
                    }
                }
            }
    }

    private fun requestDirectory(after: String?, initial: Boolean) {
        request?.cancel()
        updateState { it.copy(loading = true, failure = null) }
        request = viewModelScope.launch { acquireDirectory(after, initial) }
    }

    private suspend fun acquireDirectory(
        after: String?,
        initial: Boolean,
        revalidated: Boolean = false,
    ) {
        val result = loadEmployments(workspaceId, after)
        currentCoroutineContext().ensureActive()
        when (result) {
            is AppResult.Failed -> reportFailure(result.failure)
            is AppResult.Success -> {
                val employment =
                    if (initial) result.value.page.items.singleOrNull() else state.value.employment
                updateState {
                    it.copy(
                        directory = result.value,
                        employment = employment,
                        choosingEmployment =
                            !initial ||
                                (employment == null && result.value.page.items.isNotEmpty()),
                        loading = false,
                        failure = null,
                        restriction = if (revalidated) null else it.restriction,
                    )
                }
                observeAdmission()
                if (initial && employment != null) {
                    updateState { it.copy(loading = true) }
                    acquireEvidence(
                        DocumentEvidenceQuery(employment.id, state.value.classification)
                    )
                }
            }
        }
    }

    private fun onEmploymentRequested(event: DocumentCatalogEvent.EmploymentRequested) {
        if (opened && state.value.canChoose) {
            updateState { it.copy(choosingEmployment = true) }
            requestDirectory(null, initial = false)
        }
    }

    private fun onEmploymentDismissed(event: DocumentCatalogEvent.EmploymentDismissed) {
        if (!state.value.choosingEmployment) return
        request?.cancel()
        request = null
        updateState { it.copy(choosingEmployment = false, loading = false, failure = null) }
    }

    private fun onEmploymentPageRequested(event: DocumentCatalogEvent.EmploymentPageRequested) {
        val current = state.value
        if (
            !opened ||
                !current.choosingEmployment ||
                !current.canChoose ||
                (event.after != null && event.after != current.directory?.page?.nextCursor)
        )
            return
        requestDirectory(event.after, initial = false)
        viewModelScope.launch { emitEffect(DocumentCatalogEffect.ScrollEmploymentsToStart) }
    }

    private fun onEmploymentSelected(event: DocumentCatalogEvent.EmploymentSelected) {
        val current = state.value
        if (!opened || !current.choosingEmployment || !current.canChoose) return
        val employment =
            current.directory?.page?.items?.singleOrNull { it.id == event.id } ?: return
        expiry?.cancel()
        updateState {
            it.copy(
                employment = employment,
                choosingEmployment = false,
                catalog = null,
                expired = false,
            )
        }
        requestPage(null)
    }

    private fun onClassificationChanged(event: DocumentCatalogEvent.ClassificationChanged) {
        val current = state.value
        if (!opened || !current.canChoose || event.value == current.classification) return
        expiry?.cancel()
        updateState { it.copy(classification = event.value, catalog = null, expired = false) }
        if (current.employment != null) requestPage(null)
    }

    private fun onPageRequested(event: DocumentCatalogEvent.PageRequested) {
        val current = state.value
        if (
            !opened ||
                !current.canChoose ||
                current.choosingEmployment ||
                (event.after != null && event.after != current.catalog?.page?.nextCursor)
        )
            return
        requestPage(event.after)
        viewModelScope.launch { emitEffect(DocumentCatalogEffect.ScrollToStart) }
    }

    private fun requestPage(after: String?) {
        val current = state.value
        val employment = current.employment ?: return
        request?.cancel()
        updateState { it.copy(loading = true, failure = null) }
        request =
            viewModelScope.launch {
                acquireEvidence(DocumentEvidenceQuery(employment.id, current.classification, after))
            }
    }

    private suspend fun acquireEvidence(query: DocumentEvidenceQuery) {
        val result = loadEvidence(workspaceId, query)
        currentCoroutineContext().ensureActive()
        when (result) {
            is AppResult.Failed -> reportFailure(result.failure)
            is AppResult.Success -> {
                observeAdmission()
                expiry?.cancel()
                updateState {
                    it.copy(
                        catalog = result.value,
                        loading = false,
                        expired = false,
                        failure = null,
                    )
                }
                expiry =
                    viewModelScope.launch {
                        delay(result.value.expiresAt - Clock.System.now())
                        onEvent(DocumentCatalogEvent.CatalogExpired(result.value))
                    }
            }
        }
    }

    private fun onCatalogExpired(event: DocumentCatalogEvent.CatalogExpired) {
        if (state.value.catalog == event.snapshot) updateState { it.copy(expired = true) }
    }

    private fun onDownloadRequested(event: DocumentCatalogEvent.DownloadRequested) {
        val current = state.value
        val catalog = current.catalog ?: return
        if (
            !opened ||
                !current.canChoose ||
                current.choosingEmployment ||
                current.expired ||
                catalog.page.items.none { it.revisionId == event.revisionId }
        )
            return
        updateState { it.copy(preparingId = event.revisionId, failure = null) }
        request =
            viewModelScope.launch {
                val result = prepareDownload(workspaceId, catalog, event.revisionId)
                ensureActive()
                updateState { it.copy(preparingId = null) }
                when (result) {
                    is AppResult.Failed -> reportFailure(result.failure)
                    is AppResult.Success ->
                        emitEffect(DocumentCatalogEffect.OpenDownload(result.value.draft.id))
                }
            }
    }

    private fun reportFailure(failure: Failure) {
        if (documentReadRestricted(failure)) {
            admission?.cancel()
            request?.cancel()
            expiry?.cancel()
            updateState {
                DocumentCatalogState(
                    classification = it.classification,
                    loading = false,
                    restriction = failure,
                )
            }
        } else updateState { it.copy(loading = false, preparingId = null, failure = failure) }
    }

    private fun onVerificationRequested(event: DocumentCatalogEvent.VerificationRequested) {
        if (opened && state.value.needsVerification)
            viewModelScope.launch { emitEffect(DocumentCatalogEffect.VerifyAccount) }
    }

    private fun onBackRequested(event: DocumentCatalogEvent.BackRequested) {
        if (!opened) return
        onClosed(DocumentCatalogEvent.Closed)
        viewModelScope.launch { emitEffect(DocumentCatalogEffect.Back) }
    }
}
