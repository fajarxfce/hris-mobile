package dev.fajar.starter.documents.presentation.editor

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.usecases.*
import dev.fajar.starter.documents.domain.metadata.usecases.*
import dev.fajar.starter.documents.domain.metadata.working.entities.DocumentMetadataWorkingCopy
import dev.fajar.starter.documents.domain.metadata.working.usecases.*
import dev.fajar.starter.documents.domain.replica.policies.documentCatalogFailureClosesRead
import dev.fajar.starter.documents.domain.replica.usecases.SyncDocumentCatalog
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.usecases.RefreshWorkspace
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class DocumentMetadataEditorViewModel(
    @InjectedParam private val target: DocumentMetadataEditorTarget,
    private val observeMetadata: ObserveDocumentMetadata,
    private val refreshMetadata: RefreshDocumentMetadata,
    private val observeCommands: ObserveDocumentMetadataCommands,
    private val prepareCommand: PrepareDocumentMetadataCommand,
    private val replayCommand: ReplayDocumentMetadataCommand,
    private val reviewCommand: ReviewDocumentMetadataCommand,
    private val observeCopies: ObserveDocumentMetadataWorkingCopies,
    private val openCopy: OpenDocumentMetadataWorkingCopy,
    saveCopy: SaveDocumentMetadataWorkingCopy,
    private val discardCopy: DiscardDocumentMetadataWorkingCopy,
    private val syncCatalog: SyncDocumentCatalog,
    private val refreshWorkspace: RefreshWorkspace,
) : DocumentMetadataWorkingViewModel(target.workspaceId, saveCopy) {
    private var opened = false
    private var actionVersion = 0
    private var readVersion = 0
    private var initVersion = 0
    private var observations: Job? = null
    private var acquisition: Job? = null
    private var initialization: Job? = null
    private var action: Job? = null

    init {
        on<DocumentMetadataEditorEvent.Opened>(::onOpened)
        on<DocumentMetadataEditorEvent.Stopped>(::onStopped)
        on<DocumentMetadataEditorEvent.RefreshRequested>(::onRefreshRequested)
        on<DocumentMetadataEditorEvent.CategoryRequested>(::onCategoryRequested)
        on<DocumentMetadataEditorEvent.CategoryDismissed>(::onCategoryDismissed)
        on<DocumentMetadataEditorEvent.CategorySelected>(::onCategorySelected)
        on<DocumentMetadataEditorEvent.NumberChanged>(::onNumberChanged)
        on<DocumentMetadataEditorEvent.CountryChanged>(::onCountryChanged)
        on<DocumentMetadataEditorEvent.IssuedOnChanged>(::onIssuedOnChanged)
        on<DocumentMetadataEditorEvent.ValidUntilChanged>(::onValidUntilChanged)
        on<DocumentMetadataEditorEvent.ReasonChanged>(::onReasonChanged)
        on<DocumentMetadataEditorEvent.ConfirmRequested>(::onConfirmRequested)
        on<DocumentMetadataEditorEvent.RetryDelivery>(::onRetryDelivery)
        on<DocumentMetadataEditorEvent.ReviewRequested>(::onReviewRequested)
        on<DocumentMetadataEditorEvent.DiscardRequested>(::onDiscardRequested)
        on<DocumentMetadataEditorEvent.RestartRequested>(::onRestartRequested)
        on<DocumentMetadataEditorEvent.ReloadRequested>(::onReloadRequested)
        on<DocumentMetadataEditorEvent.RetryLocalSave>(::onRetryLocalSave)
        on<DocumentMetadataEditorEvent.DialogConfirmed>(::onDialogConfirmed)
        on<DocumentMetadataEditorEvent.DialogDismissed>(::onDialogDismissed)
        on<DocumentMetadataEditorEvent.VerificationRequested>(::onVerificationRequested)
        on<DocumentMetadataEditorEvent.BackRequested>(::onBackRequested)
    }

    private fun onOpened(event: DocumentMetadataEditorEvent.Opened) {
        if (opened || state.value.closing) return
        opened = true
        updateState { it.copy(active = true, commandsLoaded = false, copiesLoaded = false) }
        observe()
        initialize()
        acquire(false)
    }

    private fun onStopped(event: DocumentMetadataEditorEvent.Stopped) {
        opened = false
        actionVersion++
        readVersion++
        initVersion++
        observations?.cancel()
        observations = null
        acquisition?.cancel()
        acquisition = null
        initialization?.cancel()
        initialization = null
        action?.cancel()
        action = null
        persistEdits(immediate = true)
        updateState {
            it.copy(
                active = false,
                commandsLoaded = false,
                copiesLoaded = false,
                refreshing = false,
                initializing = false,
                choosingCategory = false,
                work = DocumentMetadataEditorWork.NONE,
                dialog = null,
            )
        }
    }

    private fun observe() {
        observations?.cancel()
        observations =
            viewModelScope.launch {
                launch {
                    observeMetadata(target.workspaceId, target.documentId, flowOf(null)).collect {
                        result ->
                        ensureActive()
                        when (result) {
                            is AppResult.Failed -> {
                                updateState { it.copy(view = null) }
                                report(result.failure)
                            }
                            is AppResult.Success ->
                                if (
                                    acceptAccess(result.value.access) &&
                                        state.value.restriction == null
                                )
                                    updateState { it.copy(view = result.value) }
                        }
                    }
                }
                launch {
                    observeCommands(target.workspaceId, target.documentId).collect { result ->
                        ensureActive()
                        when (result) {
                            is AppResult.Failed -> {
                                updateState { it.copy(command = null, commandsLoaded = false) }
                                report(result.failure)
                            }
                            is AppResult.Success -> {
                                val value = result.value
                                if (value.restrictions.isNotEmpty())
                                    report(value.restrictions.first().failure)
                                else if (
                                    acceptAccess(value.access) && state.value.restriction == null
                                )
                                    updateState {
                                        it.copy(
                                            command = value.records.singleOrNull(),
                                            commandsLoaded = true,
                                        )
                                    }
                            }
                        }
                    }
                }
                launch {
                    observeCopies(target.workspaceId, target.documentId).collect { result ->
                        ensureActive()
                        when (result) {
                            is AppResult.Failed -> {
                                updateState { it.copy(copiesLoaded = false) }
                                report(result.failure)
                            }
                            is AppResult.Success -> {
                                val value = result.value
                                if (value.restrictions.isNotEmpty())
                                    report(value.restrictions.first().failure)
                                else if (
                                    acceptAccess(value.access) && state.value.restriction == null
                                ) {
                                    updateState { it.copy(copiesLoaded = true) }
                                    acceptCopy(value.page.items.singleOrNull())
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun acceptAccess(access: DocumentAccess): Boolean {
        val previous = state.value.access
        if (
            previous != null &&
                (previous.accountId != access.accountId ||
                    previous.audience != access.audience ||
                    previous.companyId != access.companyId)
        ) {
            report(
                Failure(
                    FailureKind.AccessDenied,
                    "Document scope changed",
                    code = "client.document_scope_changed",
                )
            )
            return false
        }
        updateState { it.copy(access = access) }
        return true
    }

    private fun acceptCopy(copy: DocumentMetadataWorkingCopy?) {
        val current = state.value.persistence
        if (copy == current?.stored || copy == current?.pending?.next) return
        if (
            state.value.work in
                setOf(DocumentMetadataEditorWork.REVIEWING, DocumentMetadataEditorWork.DISCARDING)
        )
            return
        if (current == null || current.saved) {
            updateState { it.copy(persistence = copy?.let(::DocumentMetadataEditPersistence)) }
        } else {
            val failure =
                Failure(
                    FailureKind.Conflict,
                    "Saved draft changed",
                    code = "client.document_working_copy_changed",
                )
            updateState { it.copy(persistence = it.persistence?.copy(failure = failure)) }
            report(failure)
        }
    }

    private fun initialize() {
        val current = state.value
        if (
            current.commandsLoaded &&
                (current.command != null || (current.copiesLoaded && current.persistence != null))
        )
            return
        if (!opened || initialization?.isActive == true || state.value.restriction != null) return
        val owner = ++initVersion
        updateState { it.copy(initializing = true) }
        initialization =
            viewModelScope.launch {
                try {
                    val commands = observeCommands(target.workspaceId, target.documentId).first()
                    ensureActive()
                    if (commands is AppResult.Failed) {
                        report(commands.failure)
                        return@launch
                    }
                    val value = (commands as AppResult.Success).value
                    if (value.restrictions.isNotEmpty()) {
                        report(value.restrictions.first().failure)
                        return@launch
                    }
                    if (value.records.isNotEmpty()) return@launch
                    when (val result = openCopy(target.workspaceId, target.documentId)) {
                        is AppResult.Failed -> report(result.failure)
                        is AppResult.Success -> {
                            ensureActive()
                            if (state.value.restriction == null) acceptCopy(result.value)
                        }
                    }
                } finally {
                    if (owner == initVersion) updateState { it.copy(initializing = false) }
                }
            }
    }

    private fun onRefreshRequested(event: DocumentMetadataEditorEvent.RefreshRequested) {
        if (
            !opened ||
                acquisition?.isActive == true ||
                state.value.work != DocumentMetadataEditorWork.NONE
        )
            return
        viewModelScope.launch { emitEffect(DocumentMetadataEditorEffect.ScrollToStart) }
        acquire(true)
    }

    private fun acquire(force: Boolean) {
        if (
            !opened ||
                acquisition?.isActive == true ||
                state.value.work != DocumentMetadataEditorWork.NONE
        )
            return
        val owner = ++readVersion
        updateState { it.copy(refreshing = true, failure = null) }
        acquisition =
            viewModelScope.launch {
                try {
                    if (state.value.restriction != null) {
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
                    }
                    ensureActive()
                    val sync = syncCatalog(target.workspaceId, force)
                    ensureActive()
                    if (sync is AppResult.Failed) {
                        report(sync.failure)
                        return@launch
                    }
                    when (
                        val result = refreshMetadata(target.workspaceId, target.documentId, null)
                    ) {
                        is AppResult.Failed -> report(result.failure)
                        is AppResult.Success -> {
                            ensureActive()
                            updateState { it.copy(restriction = null, failure = null) }
                            observe()
                            initialization?.cancel()
                            initialization = null
                            initialize()
                        }
                    }
                } finally {
                    if (owner == readVersion) updateState { it.copy(refreshing = false) }
                }
            }
    }

    private fun edit(input: DocumentMetadataInput, reason: String? = null) {
        if (
            !state.value.canEdit ||
                input.identityNumber.length > 240 ||
                input.issuingCountry.length > 8 ||
                input.issuedOn.length > 16 ||
                input.validUntil.length > 16 ||
                (reason?.length ?: 0) > 1000
        )
            return
        updateState {
            it.copy(
                persistence = it.persistence?.edit(input, reason ?: it.persistence.reason),
                failure = null,
            )
        }
        persistEdits()
    }

    private fun onNumberChanged(e: DocumentMetadataEditorEvent.NumberChanged) {
        state.value.persistence?.input?.let { edit(it.copy(identityNumber = e.value)) }
    }

    private fun onCountryChanged(e: DocumentMetadataEditorEvent.CountryChanged) {
        state.value.persistence?.input?.let { edit(it.copy(issuingCountry = e.value)) }
    }

    private fun onIssuedOnChanged(e: DocumentMetadataEditorEvent.IssuedOnChanged) {
        state.value.persistence?.input?.let { edit(it.copy(issuedOn = e.value)) }
    }

    private fun onValidUntilChanged(e: DocumentMetadataEditorEvent.ValidUntilChanged) {
        state.value.persistence?.input?.let { edit(it.copy(validUntil = e.value)) }
    }

    private fun onReasonChanged(e: DocumentMetadataEditorEvent.ReasonChanged) {
        state.value.persistence?.input?.let { edit(it, e.value) }
    }

    private fun onCategoryRequested(e: DocumentMetadataEditorEvent.CategoryRequested) {
        if (state.value.canEdit) updateState { it.copy(choosingCategory = true) }
    }

    private fun onCategoryDismissed(e: DocumentMetadataEditorEvent.CategoryDismissed) {
        updateState { it.copy(choosingCategory = false) }
    }

    private fun onCategorySelected(e: DocumentMetadataEditorEvent.CategorySelected) {
        if (!state.value.choosingCategory) return
        state.value.persistence?.input?.let { edit(it.copy(category = e.value)) }
        updateState { it.copy(choosingCategory = false) }
    }

    private fun onConfirmRequested(e: DocumentMetadataEditorEvent.ConfirmRequested) {
        if (!state.value.canConfirm) return
        act(DocumentMetadataEditorWork.CONFIRMING) {
            if (flushEdits()) updateState { it.copy(dialog = DocumentMetadataEditorDialog.CONFIRM) }
            else emitEffect(DocumentMetadataEditorEffect.ScrollToStart)
        }
    }

    private fun confirm() =
        act(DocumentMetadataEditorWork.CONFIRMING) {
            if (!flushEdits()) return@act
            val local = state.value.persistence ?: return@act
            val view = state.value.view ?: return@act
            val checkpoint = view.snapshot.checkpoint ?: return@act
            // Freeze the draft's reviewed header; never silently rebase onto a newer live header.
            val reviewed =
                view.copy(
                    snapshot =
                        view.snapshot.copy(checkpoint = checkpoint.copy(head = local.stored.head))
                )
            when (
                val result = prepareCommand(target.workspaceId, reviewed, local.input, local.reason)
            ) {
                is AppResult.Failed -> {
                    report(result.failure)
                    observe()
                }
                is AppResult.Success -> {
                    ensureActive()
                    updateState { it.copy(command = result.value) }
                    deliver(result.value)
                }
            }
        }

    private fun onRetryDelivery(e: DocumentMetadataEditorEvent.RetryDelivery) {
        val current = state.value
        val record = current.command ?: return
        if (
            !current.idle ||
                !current.mayWrite ||
                record.outcome is DocumentMetadataCommandOutcome.Confirmed ||
                record.outcome is DocumentMetadataCommandOutcome.Rejected
        )
            return
        act(DocumentMetadataEditorWork.DELIVERING) { deliver(record) }
    }

    private suspend fun deliver(record: DocumentMetadataCommandRecord) {
        when (val result = replayCommand(target.workspaceId, record.command.change.operationId)) {
            is AppResult.Failed -> report(result.failure)
            is AppResult.Success -> {
                currentCoroutineContext().ensureActive()
                updateState { it.copy(command = result.value) }
            }
        }
        observe()
        emitEffect(DocumentMetadataEditorEffect.ScrollToStart)
    }

    private fun onReviewRequested(e: DocumentMetadataEditorEvent.ReviewRequested) {
        if (!state.value.canReview) return
        when (state.value.command?.outcome) {
            null,
            is DocumentMetadataCommandOutcome.Unconfirmed -> Unit
            DocumentMetadataCommandOutcome.Pending ->
                updateState { it.copy(dialog = DocumentMetadataEditorDialog.CANCEL_COMMAND) }
            else -> review()
        }
    }

    private fun review() =
        act(DocumentMetadataEditorWork.REVIEWING) {
            if (!flushEdits()) return@act
            val record = state.value.command ?: return@act
            when (val result = reviewCommand(target.workspaceId, record)) {
                is AppResult.Failed -> report(result.failure)
                is AppResult.Success -> {
                    ensureActive()
                    updateState {
                        it.copy(
                            command = null,
                            persistence = null,
                            commandsLoaded = false,
                            copiesLoaded = false,
                        )
                    }
                    if (result.value && record.outcome is DocumentMetadataCommandOutcome.Confirmed)
                        leave()
                    else observe()
                }
            }
        }

    private fun onDiscardRequested(e: DocumentMetadataEditorEvent.DiscardRequested) =
        chooseDiscard(DocumentMetadataEditorDialog.DISCARD)

    private fun onRestartRequested(e: DocumentMetadataEditorEvent.RestartRequested) =
        chooseDiscard(DocumentMetadataEditorDialog.RESTART)

    private fun chooseDiscard(dialog: DocumentMetadataEditorDialog) {
        if (state.value.idle && state.value.command == null && state.value.persistence != null)
            updateState { it.copy(dialog = dialog) }
    }

    private fun onReloadRequested(e: DocumentMetadataEditorEvent.ReloadRequested) {
        if (state.value.idle && state.value.command == null)
            updateState { it.copy(dialog = DocumentMetadataEditorDialog.RELOAD) }
    }

    private fun discard(restart: Boolean) =
        act(DocumentMetadataEditorWork.DISCARDING) {
            if (!flushEdits()) return@act
            val local = state.value.persistence ?: return@act
            when (val result = discardCopy(target.workspaceId, local.stored)) {
                is AppResult.Failed -> report(result.failure)
                is AppResult.Success -> {
                    ensureActive()
                    updateState { it.copy(persistence = null) }
                    if (!restart) leave()
                }
            }
        }

    private fun reload() =
        act(DocumentMetadataEditorWork.DISCARDING) {
            cancelWriter()
            when (val result = openCopy(target.workspaceId, target.documentId)) {
                is AppResult.Failed -> report(result.failure)
                is AppResult.Success -> {
                    ensureActive()
                    updateState {
                        it.copy(
                            persistence = DocumentMetadataEditPersistence(result.value),
                            failure = null,
                        )
                    }
                }
            }
        }

    private fun onRetryLocalSave(e: DocumentMetadataEditorEvent.RetryLocalSave) {
        if (
            !state.value.idle ||
                state.value.persistence?.failure?.code == "client.document_working_copy_changed"
        )
            return
        updateState { it.copy(persistence = it.persistence?.copy(failure = null), failure = null) }
        persistEdits(immediate = true)
    }

    private fun onDialogConfirmed(e: DocumentMetadataEditorEvent.DialogConfirmed) {
        val dialog = state.value.dialog ?: return
        updateState { it.copy(dialog = null) }
        when (dialog) {
            DocumentMetadataEditorDialog.CONFIRM -> confirm()
            DocumentMetadataEditorDialog.CANCEL_COMMAND -> review()
            DocumentMetadataEditorDialog.DISCARD -> discard(false)
            DocumentMetadataEditorDialog.RESTART -> discard(true)
            DocumentMetadataEditorDialog.RELOAD -> reload()
            DocumentMetadataEditorDialog.LEAVE_UNSAVED ->
                viewModelScope.launch {
                    cancelWriter()
                    leave()
                }
        }
    }

    private fun onDialogDismissed(e: DocumentMetadataEditorEvent.DialogDismissed) {
        updateState { it.copy(dialog = null) }
    }

    private fun onVerificationRequested(e: DocumentMetadataEditorEvent.VerificationRequested) {
        if (opened && state.value.needsVerification)
            viewModelScope.launch { emitEffect(DocumentMetadataEditorEffect.VerifyAccount) }
    }

    private fun onBackRequested(e: DocumentMetadataEditorEvent.BackRequested) {
        if (
            !opened || state.value.closing || state.value.work == DocumentMetadataEditorWork.LEAVING
        )
            return
        action?.cancel()
        action = null
        act(DocumentMetadataEditorWork.LEAVING) {
            if (state.value.restriction != null || flushEdits()) leave()
            else updateState { it.copy(dialog = DocumentMetadataEditorDialog.LEAVE_UNSAVED) }
        }
    }

    private suspend fun leave() {
        updateState { it.copy(closing = true, active = false, dialog = null) }
        observations?.cancel()
        acquisition?.cancel()
        initialization?.cancel()
        opened = false
        emitEffect(DocumentMetadataEditorEffect.Back)
    }

    private fun act(work: DocumentMetadataEditorWork, block: suspend CoroutineScope.() -> Unit) {
        if (!opened || action?.isActive == true || state.value.closing) return
        val owner = ++actionVersion
        readVersion++
        initVersion++
        acquisition?.cancel()
        acquisition = null
        initialization?.cancel()
        initialization = null
        updateState {
            it.copy(
                work = work,
                refreshing = false,
                initializing = false,
                failure = null,
                choosingCategory = false,
            )
        }
        action =
            viewModelScope.launch {
                try {
                    block()
                } finally {
                    if (owner == actionVersion) {
                        updateState { it.copy(work = DocumentMetadataEditorWork.NONE) }
                        if (
                            opened &&
                                state.value.command?.outcome is
                                    DocumentMetadataCommandOutcome.Confirmed
                        )
                            acquire(true)
                        else if (
                            opened &&
                                state.value.command == null &&
                                state.value.persistence == null &&
                                work in
                                    setOf(
                                        DocumentMetadataEditorWork.REVIEWING,
                                        DocumentMetadataEditorWork.DISCARDING,
                                    )
                        )
                            initialize()
                    }
                }
            }
    }

    override fun report(failure: Failure) {
        val denied =
            documentCatalogFailureClosesRead(failure) || failure.code == "document_not_found"
        if (denied) {
            cancelWriter()
            updateState {
                it.copy(
                    persistence = null,
                    command = null,
                    view = null,
                    restriction = failure,
                    commandsLoaded = false,
                    copiesLoaded = false,
                    failure = failure,
                    dialog = null,
                    choosingCategory = false,
                )
            }
        } else updateState { it.copy(failure = failure) }
        if (opened) viewModelScope.launch { emitEffect(DocumentMetadataEditorEffect.ScrollToStart) }
    }
}
