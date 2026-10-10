package dev.fajar.starter.files.presentation.queue

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.security.domain.access.entities.Permission
import dev.fajar.starter.security.domain.access.usecases.ObservePermission
import dev.fajar.starter.security.domain.access.usecases.RefreshAccess
import dev.fajar.starter.transfers.domain.usecases.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class FilesViewModel(
    private val observe: ObserveTransfers,
    private val upload: EnqueueUpload,
    private val download: EnqueueDownload,
    private val change: ChangeTransfer,
    private val permission: ObservePermission,
    private val refreshAccess: RefreshAccess,
) : MviViewModel<FilesState, FilesEvent, FilesEffect>(FilesState()) {
    private var observation: Job? = null

    init {
        on<FilesEvent.Started>(::onStarted)
        on<FilesEvent.Stopped>(::onStopped)
        on<FilesEvent.UploadRequested>(::onUploadRequested)
        on<FilesEvent.DownloadRequested>(::onDownloadRequested)
        on<FilesEvent.TransferChanged>(::onTransferChanged)
        on<FilesEvent.BackRequested>(::onBackRequested)
    }

    @OptIn(FlowPreview::class)
    private fun onStarted(event: FilesEvent.Started) {
        observation?.cancel()
        observation =
            viewModelScope.launch {
                launch {
                    observe()
                        .map { result ->
                            when (result) {
                                is AppResult.Failed -> result
                                is AppResult.Success ->
                                    AppResult.Success(result.value.map { it.toRow() })
                            }
                        }
                        .distinctUntilChanged()
                        .sample(200)
                        .collect { result ->
                            when (result) {
                                is AppResult.Success ->
                                    updateState { it.copy(rows = result.value, loading = false) }
                                is AppResult.Failed ->
                                    updateState { it.copy(error = result.failure, loading = false) }
                            }
                        }
                }
                launch {
                    combine(
                            permission(Permission.UploadFile),
                            permission(Permission.DownloadFile),
                        ) { upload, download ->
                            Pair(
                                (upload as? AppResult.Success)?.value == true,
                                (download as? AppResult.Success)?.value == true,
                            )
                        }
                        .collect { (upload, download) ->
                            updateState { it.copy(canUpload = upload, canDownload = download) }
                        }
                }
                when (val result = refreshAccess()) {
                    is AppResult.Failed -> updateState { it.copy(error = result.failure) }
                    is AppResult.Success -> Unit
                }
            }
    }

    private fun onStopped(event: FilesEvent.Stopped) {
        observation?.cancel()
        observation = null
    }

    private fun onUploadRequested(event: FilesEvent.UploadRequested) {
        if (state.value.importing) return
        updateState { it.copy(importing = true, error = null) }
        viewModelScope.launch {
            try {
                when (val result = upload("sample-report")) {
                    is AppResult.Failed -> updateState { it.copy(error = result.failure) }
                    is AppResult.Success ->
                        updateState { it.copy(error = result.value.schedulingFailure) }
                }
            } finally {
                updateState { it.copy(importing = false) }
            }
        }
    }

    private fun onDownloadRequested(event: FilesEvent.DownloadRequested) {
        if (state.value.downloading) return
        updateState { it.copy(downloading = true, error = null) }
        viewModelScope.launch {
            try {
                when (val result = download("sample-report")) {
                    is AppResult.Failed -> updateState { it.copy(error = result.failure) }
                    is AppResult.Success ->
                        updateState { it.copy(error = result.value.schedulingFailure) }
                }
            } finally {
                updateState { it.copy(downloading = false) }
            }
        }
    }

    private fun onTransferChanged(event: FilesEvent.TransferChanged) {
        if (state.value.changingId != null) return
        updateState { it.copy(changingId = event.id, error = null) }
        viewModelScope.launch {
            try {
                when (val result = change(event.id, event.action)) {
                    is AppResult.Failed -> updateState { it.copy(error = result.failure) }
                    is AppResult.Success -> Unit
                }
            } finally {
                updateState { it.copy(changingId = null) }
            }
        }
    }

    private fun onBackRequested(event: FilesEvent.BackRequested) {
        viewModelScope.launch { emitEffect(FilesEffect.Back) }
    }
}
