package dev.fajar.starter.documents.presentation.files

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.usecases.*
import dev.fajar.starter.documents.presentation.access.documentReadRestricted
import dev.fajar.starter.documents.presentation.files.mappers.toFileItem
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.usecases.RefreshWorkspace
import kotlinx.coroutines.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class DocumentFilesViewModel(
    @InjectedParam private val workspaceId: String,
    private val observeFiles: ObserveDocumentFiles,
    private val importFile: ImportDocumentFile,
    private val removeFile: RemoveDocumentFile,
    private val refreshWorkspace: RefreshWorkspace,
) :
    MviViewModel<DocumentFilesState, DocumentFilesEvent, DocumentFilesEffect>(
        DocumentFilesState()
    ) {
    private var opened = false
    private var observation: Job? = null
    private var action: Job? = null

    init {
        on<DocumentFilesEvent.Opened>(::onOpened)
        on<DocumentFilesEvent.Closed>(::onClosed)
        on<DocumentFilesEvent.RetryRequested>(::onRetryRequested)
        on<DocumentFilesEvent.ImportRequested>(::onImportRequested)
        on<DocumentFilesEvent.FileRequested>(::onFileRequested)
        on<DocumentFilesEvent.RemovalRequested>(::onRemovalRequested)
        on<DocumentFilesEvent.RemovalDismissed>(::onRemovalDismissed)
        on<DocumentFilesEvent.RemovalConfirmed>(::onRemovalConfirmed)
        on<DocumentFilesEvent.VerificationRequested>(::onVerificationRequested)
        on<DocumentFilesEvent.BackRequested>(::onBackRequested)
    }

    private fun onOpened(event: DocumentFilesEvent.Opened) {
        if (opened) return
        opened = true
        if (state.value.restriction == null) observe()
    }

    private fun onClosed(event: DocumentFilesEvent.Closed) {
        opened = false
        observation?.cancel()
        observation = null
        action?.cancel()
        action = null
        updateState {
            it.copy(
                content = null,
                items = emptyList(),
                importing = false,
                removing = false,
                revalidating = false,
                removal = null,
            )
        }
    }

    private fun observe(revalidated: Boolean = false) {
        observation?.cancel()
        updateState { it.copy(loading = true, failure = null, readFailure = null) }
        observation =
            viewModelScope.launch {
                observeFiles(workspaceId).collect { result ->
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> {
                            updateState {
                                it.copy(content = null, items = emptyList(), loading = false)
                            }
                            if (documentReadRestricted(result.failure))
                                reportFailure(result.failure)
                            else updateState { it.copy(readFailure = result.failure) }
                        }
                        is AppResult.Success ->
                            updateState {
                                it.copy(
                                    content = result.value,
                                    items = result.value.files.map { file -> file.toFileItem() },
                                    loading = false,
                                    readFailure = null,
                                    restriction = if (revalidated) null else it.restriction,
                                )
                            }
                    }
                }
            }
    }

    private fun onRetryRequested(event: DocumentFilesEvent.RetryRequested) {
        if (!opened || state.value.busy) return
        if (state.value.restriction == null) {
            observe()
            return
        }
        updateState { it.copy(revalidating = true, failure = null) }
        action =
            viewModelScope.launch {
                try {
                    val result = refreshWorkspace()
                    ensureActive()
                    when (result) {
                        SyncResult.Complete -> observe(revalidated = true)
                        is SyncResult.Blocked -> reportFailure(result.failure)
                        is SyncResult.Retry -> result.failure?.let(::reportFailure)
                    }
                } finally {
                    if (isActive) updateState { it.copy(revalidating = false) }
                }
            }
    }

    private fun onImportRequested(event: DocumentFilesEvent.ImportRequested) {
        if (!opened || !state.value.canImport || state.value.removal != null) return
        updateState { it.copy(importing = true, failure = null) }
        action =
            viewModelScope.launch {
                try {
                    val result = importFile(workspaceId)
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success ->
                            result.value?.let {
                                emitEffect(DocumentFilesEffect.OpenFile(it.draft.id))
                            }
                    }
                } finally {
                    if (isActive) updateState { it.copy(importing = false) }
                }
            }
    }

    private fun onRemovalRequested(event: DocumentFilesEvent.RemovalRequested) {
        val current = state.value
        if (!opened || current.busy || current.restriction != null) return
        if (current.items.none { it.id == event.id && it.removable }) return
        val file = current.content?.files?.singleOrNull { it.draft.id == event.id } ?: return
        updateState { it.copy(removal = file, failure = null) }
    }

    private fun onRemovalDismissed(event: DocumentFilesEvent.RemovalDismissed) {
        if (!state.value.removing) updateState { it.copy(removal = null) }
    }

    private fun onFileRequested(event: DocumentFilesEvent.FileRequested) {
        val current = state.value
        if (
            opened &&
                !current.busy &&
                current.restriction == null &&
                current.items.any { it.id == event.id && it.openable }
        )
            viewModelScope.launch {
                val file =
                    current.content?.files?.singleOrNull { it.draft.id == event.id }
                        ?: return@launch
                emitEffect(
                    if (file.draft.download != null) DocumentFilesEffect.OpenDownload(event.id)
                    else DocumentFilesEffect.OpenFile(event.id)
                )
            }
    }

    private fun onRemovalConfirmed(event: DocumentFilesEvent.RemovalConfirmed) {
        val current = state.value
        if (!opened || current.busy || current.restriction != null) return
        val file = current.removal ?: return
        updateState { it.copy(removing = true, failure = null) }
        action =
            viewModelScope.launch {
                try {
                    val result = removeFile(workspaceId, file.draft.id, file.revision)
                    ensureActive()
                    updateState { it.copy(removal = null) }
                    if (result is AppResult.Failed) reportFailure(result.failure)
                } finally {
                    if (isActive) updateState { it.copy(removing = false) }
                }
            }
    }

    private fun reportFailure(failure: Failure) {
        if (documentReadRestricted(failure)) {
            observation?.cancel()
            action?.cancel()
            updateState {
                it.copy(
                    content = null,
                    items = emptyList(),
                    restriction = failure,
                    failure = null,
                    loading = false,
                    importing = false,
                    removing = false,
                    revalidating = false,
                    removal = null,
                )
            }
        } else updateState { it.copy(failure = failure) }
    }

    private fun onVerificationRequested(event: DocumentFilesEvent.VerificationRequested) {
        if (opened && state.value.needsVerification)
            viewModelScope.launch { emitEffect(DocumentFilesEffect.VerifyAccount) }
    }

    private fun onBackRequested(event: DocumentFilesEvent.BackRequested) {
        if (!opened) return
        onClosed(DocumentFilesEvent.Closed)
        viewModelScope.launch { emitEffect(DocumentFilesEffect.Back) }
    }
}
