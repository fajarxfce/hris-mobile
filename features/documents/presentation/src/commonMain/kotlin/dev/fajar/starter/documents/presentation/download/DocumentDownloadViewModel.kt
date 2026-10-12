package dev.fajar.starter.documents.presentation.download

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.LocalDocumentFile
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
class DocumentDownloadViewModel(
    @InjectedParam private val target: DocumentDownloadTarget,
    private val observeFiles: ObserveDocumentFiles,
    private val downloadFile: DownloadDocumentFile,
    private val exportFile: ExportDocumentFile,
    private val refreshWorkspace: RefreshWorkspace,
) :
    MviViewModel<DocumentDownloadState, DocumentDownloadEvent, DocumentDownloadEffect>(
        DocumentDownloadState()
    ) {
    private var opened = false
    private var startPending = target.start
    private var observation: Job? = null
    private var action: Job? = null

    init {
        on<DocumentDownloadEvent.Opened>(::onOpened)
        on<DocumentDownloadEvent.Closed>(::onClosed)
        on<DocumentDownloadEvent.DownloadRequested>(::onDownloadRequested)
        on<DocumentDownloadEvent.SaveRequested>(::onSaveRequested)
        on<DocumentDownloadEvent.StopRequested>(::onStopRequested)
        on<DocumentDownloadEvent.RetryRequested>(::onRetryRequested)
        on<DocumentDownloadEvent.VerificationRequested>(::onVerificationRequested)
        on<DocumentDownloadEvent.BackRequested>(::onBackRequested)
    }

    private fun onOpened(event: DocumentDownloadEvent.Opened) {
        if (opened) return
        opened = true
        if (state.value.restriction == null) observe()
    }

    private fun onClosed(event: DocumentDownloadEvent.Closed) {
        opened = false
        observation?.cancel()
        observation = null
        action?.cancel()
        action = null
        updateState { it.copy(file = null, item = null, loading = false, work = null) }
    }

    private fun observe() {
        observation?.cancel()
        updateState { it.copy(loading = true) }
        observation =
            viewModelScope.launch {
                observeFiles(target.workspaceId).collect { result ->
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> {
                            action?.cancel()
                            updateState {
                                it.copy(file = null, item = null, work = null, loading = false)
                            }
                            reportFailure(result.failure)
                        }
                        is AppResult.Success -> {
                            val file =
                                result.value.files.singleOrNull { it.draft.id == target.fileId }
                            if (file == null || file.draft.download == null) {
                                action?.cancel()
                                updateState {
                                    it.copy(file = null, item = null, work = null, loading = false)
                                }
                                reportFailure(
                                    Failure(
                                        FailureKind.Unavailable,
                                        "The local file is unavailable.",
                                        code = "client.file_unavailable",
                                    )
                                )
                            } else {
                                acceptFile(file)
                                if (startPending) {
                                    startPending = false
                                    if (file.sha256 == null)
                                        onEvent(DocumentDownloadEvent.DownloadRequested)
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun acceptFile(file: LocalDocumentFile) {
        updateState { it.copy(file = file, item = file.toFileItem(), loading = false) }
    }

    private fun onDownloadRequested(event: DocumentDownloadEvent.DownloadRequested) {
        if (!opened || !state.value.canDownload) return
        updateState {
            it.copy(work = DocumentDownloadWork.Downloading, saved = false, failure = null)
        }
        action = viewModelScope.launch { acquireDownload() }
    }

    private suspend fun acquireDownload(revalidated: Boolean = false) {
        val result = downloadFile(target.workspaceId, target.fileId)
        currentCoroutineContext().ensureActive()
        when (result) {
            is AppResult.Failed -> reportFailure(result.failure)
            is AppResult.Success -> {
                updateState {
                    it.copy(
                        restriction = if (revalidated) null else it.restriction,
                        work = null,
                        failure = null,
                    )
                }
                acceptFile(result.value)
                observe()
            }
        }
    }

    private fun onSaveRequested(event: DocumentDownloadEvent.SaveRequested) {
        if (!opened || !state.value.canSave) return
        updateState { it.copy(work = DocumentDownloadWork.Saving, saved = false, failure = null) }
        action =
            viewModelScope.launch {
                val result = exportFile(target.workspaceId, target.fileId)
                ensureActive()
                when (result) {
                    is AppResult.Failed -> reportFailure(result.failure)
                    is AppResult.Success ->
                        updateState { it.copy(work = null, saved = result.value) }
                }
            }
    }

    private fun onStopRequested(event: DocumentDownloadEvent.StopRequested) {
        if (!opened || state.value.work == null) return
        action?.cancel()
        action = null
        updateState { it.copy(work = null, saved = false, failure = null) }
    }

    private fun onRetryRequested(event: DocumentDownloadEvent.RetryRequested) {
        if (!opened || state.value.busy) return
        val restricted = state.value.restriction != null
        updateState { it.copy(work = DocumentDownloadWork.Revalidating, failure = null) }
        action =
            viewModelScope.launch {
                if (restricted) {
                    when (val result = refreshWorkspace()) {
                        SyncResult.Complete -> ensureActive()
                        is SyncResult.Blocked -> {
                            ensureActive()
                            reportFailure(result.failure)
                            return@launch
                        }
                        is SyncResult.Retry -> {
                            ensureActive()
                            updateState { it.copy(work = null) }
                            result.failure?.let(::reportFailure)
                            return@launch
                        }
                    }
                }
                // A successful workspace refresh alone cannot undo a document-content denial.
                acquireDownload(revalidated = true)
            }
    }

    private fun reportFailure(failure: Failure) {
        if (documentReadRestricted(failure)) {
            observation?.cancel()
            action?.cancel()
            startPending = false
            updateState { DocumentDownloadState(loading = false, restriction = failure) }
        } else updateState { it.copy(work = null, loading = false, failure = failure) }
    }

    private fun onVerificationRequested(event: DocumentDownloadEvent.VerificationRequested) {
        if (opened && state.value.needsVerification)
            viewModelScope.launch { emitEffect(DocumentDownloadEffect.VerifyAccount) }
    }

    private fun onBackRequested(event: DocumentDownloadEvent.BackRequested) {
        if (!opened) return
        onClosed(DocumentDownloadEvent.Closed)
        viewModelScope.launch { emitEffect(DocumentDownloadEffect.Back) }
    }
}
