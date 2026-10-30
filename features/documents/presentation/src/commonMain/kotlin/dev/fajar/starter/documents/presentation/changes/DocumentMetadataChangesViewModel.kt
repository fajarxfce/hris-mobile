package dev.fajar.starter.documents.presentation.changes

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.commands.usecases.ObserveDocumentMetadataCommands
import dev.fajar.starter.documents.domain.metadata.working.usecases.ObserveDocumentMetadataWorkingCopies
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
class DocumentMetadataChangesViewModel(
    @InjectedParam private val workspaceId: String,
    private val commands: ObserveDocumentMetadataCommands,
    private val copies: ObserveDocumentMetadataWorkingCopies,
    private val refreshWorkspace: RefreshWorkspace,
    private val syncCatalog: SyncDocumentCatalog,
) :
    MviViewModel<
        DocumentMetadataChangesState,
        DocumentMetadataChangesEvent,
        DocumentMetadataChangesEffect,
    >(DocumentMetadataChangesState()) {
    private var opened = false
    private var owner: DocumentAccess? = null
    private var observation: Job? = null
    private var refresh: Job? = null
    private var refreshVersion = 0
    private val after = MutableStateFlow<String?>(null)

    init {
        on<DocumentMetadataChangesEvent.Opened>(::onOpened)
        on<DocumentMetadataChangesEvent.Closed>(::onClosed)
        on<DocumentMetadataChangesEvent.RefreshRequested>(::onRefreshRequested)
        on<DocumentMetadataChangesEvent.EditorRequested>(::onEditorRequested)
        on<DocumentMetadataChangesEvent.RecoveryRequested>(::onRecoveryRequested)
        on<DocumentMetadataChangesEvent.PageRequested>(::onPageRequested)
        on<DocumentMetadataChangesEvent.VerificationRequested>(::onVerificationRequested)
        on<DocumentMetadataChangesEvent.BackRequested>(::onBackRequested)
    }

    private fun onOpened(e: DocumentMetadataChangesEvent.Opened) {
        if (opened) return
        opened = true
        observe()
    }

    private fun onClosed(e: DocumentMetadataChangesEvent.Closed) {
        opened = false
        refreshVersion++
        observation?.cancel()
        observation = null
        refresh?.cancel()
        refresh = null
        updateState {
            it.copy(
                commands = null,
                copies = null,
                loadingCommands = false,
                loadingCopies = false,
                refreshing = false,
            )
        }
    }

    private fun observe() {
        observation?.cancel()
        updateState { it.copy(loadingCommands = true, loadingCopies = true) }
        observation =
            viewModelScope.launch {
                launch {
                    commands(workspaceId).collect { result ->
                        ensureActive()
                        when (result) {
                            is AppResult.Failed -> {
                                updateState { it.copy(commands = null, loadingCommands = false) }
                                report(result.failure)
                            }
                            is AppResult.Success ->
                                if (admit(result.value.access))
                                    updateState {
                                        it.copy(commands = result.value, loadingCommands = false)
                                    }
                        }
                    }
                }
                launch {
                    copies(workspaceId, after = after).collect { result ->
                        ensureActive()
                        when (result) {
                            is AppResult.Failed -> {
                                updateState { it.copy(copies = null, loadingCopies = false) }
                                report(result.failure)
                            }
                            is AppResult.Success ->
                                if (
                                    admit(result.value.access) &&
                                        result.value.afterDocumentId == state.value.after
                                )
                                    updateState {
                                        it.copy(copies = result.value, loadingCopies = false)
                                    }
                        }
                    }
                }
            }
    }

    private fun admit(access: DocumentAccess): Boolean {
        val previous = owner
        if (
            previous != null &&
                (access.accountId != previous.accountId ||
                    access.audience != previous.audience ||
                    access.companyId != previous.companyId)
        ) {
            report(
                Failure(
                    FailureKind.AccessDenied,
                    "Scope changed",
                    code = "client.document_scope_changed",
                )
            )
            return false
        }
        owner = access
        return state.value.restriction == null
    }

    private fun report(failure: Failure) {
        if (documentCatalogFailureClosesRead(failure))
            updateState {
                it.copy(
                    commands = null,
                    copies = null,
                    restriction = failure,
                    failure = failure,
                    loadingCommands = false,
                    loadingCopies = false,
                )
            }
        else updateState { it.copy(failure = failure) }
    }

    private fun onRefreshRequested(e: DocumentMetadataChangesEvent.RefreshRequested) {
        if (!opened || refresh?.isActive == true) return
        val denied = state.value.restriction != null
        updateState { it.copy(failure = null) }
        observe()
        if (!denied) return
        val owner = ++refreshVersion
        updateState { it.copy(refreshing = true) }
        refresh =
            viewModelScope.launch {
                try {
                    when (val result = refreshWorkspace()) {
                        is SyncResult.Blocked -> {
                            report(result.failure)
                            return@launch
                        }
                        is SyncResult.Retry -> {
                            result.failure?.let(::report)
                            return@launch
                        }
                        SyncResult.Complete -> Unit
                    }
                    ensureActive()
                    when (val result = syncCatalog(workspaceId, true)) {
                        is AppResult.Failed -> report(result.failure)
                        is AppResult.Success -> {
                            ensureActive()
                            updateState { it.copy(restriction = null, failure = null) }
                            observe()
                        }
                    }
                } finally {
                    if (owner == refreshVersion) updateState { it.copy(refreshing = false) }
                }
            }
    }

    private fun onEditorRequested(e: DocumentMetadataChangesEvent.EditorRequested) {
        val current = state.value
        if (
            !opened ||
                current.restriction != null ||
                (current.records.none { it.command.change.documentId == e.documentId } &&
                    current.drafts.none { it.head.id == e.documentId })
        )
            return
        onClosed(DocumentMetadataChangesEvent.Closed)
        viewModelScope.launch { emitEffect(DocumentMetadataChangesEffect.Editor(e.documentId)) }
    }

    private fun onRecoveryRequested(e: DocumentMetadataChangesEvent.RecoveryRequested) {
        if (
            !opened ||
                state.value.restriction != null ||
                state.value.unavailable.none { it.documentId == e.documentId }
        )
            return
        onClosed(DocumentMetadataChangesEvent.Closed)
        viewModelScope.launch { emitEffect(DocumentMetadataChangesEffect.Recovery(e.documentId)) }
    }

    private fun onPageRequested(e: DocumentMetadataChangesEvent.PageRequested) {
        val current = state.value
        if (
            !opened ||
                current.restriction != null ||
                current.loadingCopies ||
                e.after == current.after ||
                (e.after != null && e.after != current.copies?.page?.nextDocumentId)
        )
            return
        updateState { it.copy(after = e.after, copies = null, loadingCopies = true) }
        after.value = e.after
        viewModelScope.launch { emitEffect(DocumentMetadataChangesEffect.ScrollToStart) }
    }

    private fun onVerificationRequested(e: DocumentMetadataChangesEvent.VerificationRequested) {
        if (opened && state.value.needsVerification)
            viewModelScope.launch { emitEffect(DocumentMetadataChangesEffect.VerifyAccount) }
    }

    private fun onBackRequested(e: DocumentMetadataChangesEvent.BackRequested) {
        if (!opened) return
        onClosed(DocumentMetadataChangesEvent.Closed)
        viewModelScope.launch { emitEffect(DocumentMetadataChangesEffect.Back) }
    }
}
