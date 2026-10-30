package dev.fajar.starter.documents.presentation.detail

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.isDocumentUploadRejected
import dev.fajar.starter.documents.domain.usecases.*
import dev.fajar.starter.documents.presentation.access.documentReadRestricted
import dev.fajar.starter.documents.presentation.detail.mappers.createDocumentUploadForm
import dev.fajar.starter.documents.presentation.files.mappers.toFileItem
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.usecases.RefreshWorkspace
import kotlinx.coroutines.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class DocumentFileViewModel(
    @InjectedParam private val target: DocumentFileTarget,
    private val observeFiles: ObserveDocumentFiles,
    private val loadPreparation: LoadDocumentUploadPreparation,
    private val prepareUpload: PrepareDocumentUpload,
    private val replayUpload: ReplayDocumentUpload,
    private val reviewUpload: ReviewDocumentUpload,
    private val refreshWorkspace: RefreshWorkspace,
) : MviViewModel<DocumentFileState, DocumentFileEvent, DocumentFileEffect>(DocumentFileState()) {
    private var opened = false
    private var observation: Job? = null
    private var formRequest: Job? = null
    private var action: Job? = null

    init {
        on<DocumentFileEvent.Opened>(::onOpened)
        on<DocumentFileEvent.Closed>(::onClosed)
        on<DocumentFileEvent.RetryRequested>(::onRetryRequested)
        on<DocumentFileEvent.FormReloadRequested>(::onFormReloadRequested)
        on<DocumentFileEvent.TitleChanged>(::onTitleChanged)
        on<DocumentFileEvent.ReasonChanged>(::onReasonChanged)
        on<DocumentFileEvent.ClassificationChanged>(::onClassificationChanged)
        on<DocumentFileEvent.CategoryRequested>(::onCategoryRequested)
        on<DocumentFileEvent.CategoryDismissed>(::onCategoryDismissed)
        on<DocumentFileEvent.CategorySelected>(::onCategorySelected)
        on<DocumentFileEvent.DocumentNumberChanged>(::onDocumentNumberChanged)
        on<DocumentFileEvent.IssuingCountryChanged>(::onIssuingCountryChanged)
        on<DocumentFileEvent.IssueDateChanged>(::onIssueDateChanged)
        on<DocumentFileEvent.ValidUntilChanged>(::onValidUntilChanged)
        on<DocumentFileEvent.EmploymentRequested>(::onEmploymentRequested)
        on<DocumentFileEvent.EmploymentDismissed>(::onEmploymentDismissed)
        on<DocumentFileEvent.EmploymentPageRequested>(::onEmploymentPageRequested)
        on<DocumentFileEvent.EmploymentSelected>(::onEmploymentSelected)
        on<DocumentFileEvent.UploadConfirmed>(::onUploadConfirmed)
        on<DocumentFileEvent.ResumeRequested>(::onResumeRequested)
        on<DocumentFileEvent.StatusRequested>(::onStatusRequested)
        on<DocumentFileEvent.ValidationRetryRequested>(::onValidationRetryRequested)
        on<DocumentFileEvent.CancellationRequested>(::onCancellationRequested)
        on<DocumentFileEvent.CancellationDismissed>(::onCancellationDismissed)
        on<DocumentFileEvent.CancellationReasonChanged>(::onCancellationReasonChanged)
        on<DocumentFileEvent.CancellationConfirmed>(::onCancellationConfirmed)
        on<DocumentFileEvent.VerificationRequested>(::onVerificationRequested)
        on<DocumentFileEvent.BackRequested>(::onBackRequested)
    }

    private fun onOpened(event: DocumentFileEvent.Opened) {
        if (opened) return
        opened = true
        if (state.value.restriction == null) observe()
    }

    private fun onClosed(event: DocumentFileEvent.Closed) {
        opened = false
        observation?.cancel()
        observation = null
        formRequest?.cancel()
        formRequest = null
        action?.cancel()
        action = null
        // Retain unsent text across Activity recreation, while requiring a fresh admitted read
        // before displaying it again. Clearing the navigation owner releases the draft as well.
        updateState {
            it.copy(
                file = null,
                item = null,
                choices = null,
                loading = false,
                formLoading = false,
                working = false,
                revalidating = false,
                writeAllowed = false,
                choosingEmployment = false,
                choosingCategory = false,
                cancellationReason = null,
            )
        }
    }

    private fun observe(revalidated: Boolean = false) {
        observation?.cancel()
        updateState { it.copy(loading = true, readFailure = null, formFailure = null) }
        observation =
            viewModelScope.launch {
                observeFiles(target.workspaceId).collect { result ->
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> {
                            formRequest?.cancel()
                            action?.cancel()
                            updateState {
                                it.copy(
                                    file = null,
                                    item = null,
                                    form = null,
                                    choices = null,
                                    loading = false,
                                    formLoading = false,
                                    working = false,
                                    writeAllowed = false,
                                    choosingEmployment = false,
                                    choosingCategory = false,
                                    cancellationReason = null,
                                )
                            }
                            if (documentReadRestricted(result.failure))
                                reportFailure(result.failure)
                            else updateState { it.copy(readFailure = result.failure) }
                        }
                        is AppResult.Success -> acceptFiles(result.value, revalidated)
                    }
                }
            }
    }

    private fun acceptFiles(content: DocumentLocalFiles, revalidated: Boolean) {
        val file = content.files.firstOrNull { it.draft.id == target.fileId }
        if (file == null || file.draft.download != null) {
            formRequest?.cancel()
            action?.cancel()
            updateState {
                DocumentFileState(
                    loading = false,
                    failure =
                        Failure(
                            FailureKind.Unavailable,
                            "The local file is unavailable.",
                            code = "client.file_unavailable",
                        ),
                )
            }
            return
        }
        val editable = file.upload == null || isDocumentUploadRejected(file.upload)
        if (!editable) formRequest?.cancel()
        updateState {
            it.copy(
                file = file,
                item = file.toFileItem(),
                loading = false,
                readFailure = null,
                restriction = if (revalidated) null else it.restriction,
                writeAllowed = "documents.self.upload" in content.access.permissions,
                form = if (editable) it.form else null,
                choices = if (editable) it.choices else null,
                choosingEmployment = editable && it.choosingEmployment,
                choosingCategory = editable && it.choosingCategory,
                formLoading = editable && it.formLoading,
            )
        }
        if (
            editable &&
                file.sha256 != null &&
                state.value.writeAllowed &&
                state.value.form == null &&
                !state.value.formLoading &&
                state.value.formFailure == null
        )
            loadForm(null)
    }

    private fun onRetryRequested(event: DocumentFileEvent.RetryRequested) {
        if (!opened || state.value.busy) return
        if (state.value.restriction == null) {
            observe()
            return
        }
        updateState { it.copy(revalidating = true, failure = null) }
        action =
            viewModelScope.launch {
                try {
                    when (val result = refreshWorkspace()) {
                        SyncResult.Complete -> {
                            ensureActive()
                            observe(revalidated = true)
                        }
                        is SyncResult.Blocked -> {
                            ensureActive()
                            reportFailure(result.failure)
                        }
                        is SyncResult.Retry -> {
                            ensureActive()
                            result.failure?.let(::reportFailure)
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(revalidating = false) }
                }
            }
    }

    private fun onFormReloadRequested(event: DocumentFileEvent.FormReloadRequested) {
        if (opened && state.value.canWrite && state.value.canPrepareUpload)
            loadForm(state.value.form?.preparation?.after)
    }

    private fun loadForm(after: String?, choices: Boolean = false) {
        formRequest?.cancel()
        updateState { it.copy(formLoading = true, formFailure = null, failure = null) }
        formRequest =
            viewModelScope.launch {
                val result = loadPreparation(target.workspaceId, target.fileId, after)
                ensureActive()
                when (result) {
                    is AppResult.Failed -> {
                        updateState { it.copy(formLoading = false) }
                        if (documentReadRestricted(result.failure)) reportFailure(result.failure)
                        else updateState { it.copy(formFailure = result.failure) }
                    }
                    is AppResult.Success ->
                        updateState { current ->
                            if (choices) current.copy(choices = result.value, formLoading = false)
                            else
                                current.copy(
                                    formLoading = false,
                                    form = createDocumentUploadForm(result.value, current.form),
                                )
                        }
                }
            }
    }

    private fun onTitleChanged(event: DocumentFileEvent.TitleChanged) {
        if (opened && !state.value.working)
            updateState {
                it.copy(form = it.form?.copy(title = event.value.take(160)), failure = null)
            }
    }

    private fun onReasonChanged(event: DocumentFileEvent.ReasonChanged) {
        if (opened && !state.value.working)
            updateState {
                it.copy(form = it.form?.copy(reason = event.value.take(1_000)), failure = null)
            }
    }

    private fun onClassificationChanged(event: DocumentFileEvent.ClassificationChanged) {
        if (opened && !state.value.working)
            updateState {
                it.copy(form = it.form?.copy(classification = event.value), failure = null)
            }
    }

    private fun metadataEditable(): Boolean =
        opened &&
            state.value.canWrite &&
            !state.value.choosingEmployment &&
            state.value.form?.preparation?.employments?.metadataSupported == true

    private fun onCategoryRequested(event: DocumentFileEvent.CategoryRequested) {
        if (metadataEditable()) updateState { it.copy(choosingCategory = true) }
    }

    private fun onCategoryDismissed(event: DocumentFileEvent.CategoryDismissed) {
        updateState { it.copy(choosingCategory = false) }
    }

    private fun onCategorySelected(event: DocumentFileEvent.CategorySelected) {
        if (metadataEditable() && state.value.choosingCategory)
            updateState {
                it.copy(
                    choosingCategory = false,
                    form =
                        it.form?.let { form ->
                            form.copy(metadata = form.metadata.copy(category = event.value))
                        },
                    failure = null,
                )
            }
    }

    private fun onDocumentNumberChanged(event: DocumentFileEvent.DocumentNumberChanged) {
        if (metadataEditable())
            updateState {
                it.copy(
                    form =
                        it.form?.let { form ->
                            form.copy(
                                metadata =
                                    form.metadata.copy(identityNumber = event.value.take(241))
                            )
                        },
                    failure = null,
                )
            }
    }

    private fun onIssuingCountryChanged(event: DocumentFileEvent.IssuingCountryChanged) {
        if (metadataEditable())
            updateState {
                it.copy(
                    form =
                        it.form?.let { form ->
                            form.copy(
                                metadata = form.metadata.copy(issuingCountry = event.value.take(9))
                            )
                        },
                    failure = null,
                )
            }
    }

    private fun onIssueDateChanged(event: DocumentFileEvent.IssueDateChanged) {
        if (metadataEditable())
            updateState {
                it.copy(
                    form =
                        it.form?.let { form ->
                            form.copy(
                                metadata = form.metadata.copy(issuedOn = event.value.take(17))
                            )
                        },
                    failure = null,
                )
            }
    }

    private fun onValidUntilChanged(event: DocumentFileEvent.ValidUntilChanged) {
        if (metadataEditable())
            updateState {
                it.copy(
                    form =
                        it.form?.let { form ->
                            form.copy(
                                metadata = form.metadata.copy(validUntil = event.value.take(17))
                            )
                        },
                    failure = null,
                )
            }
    }

    private fun onEmploymentRequested(event: DocumentFileEvent.EmploymentRequested) {
        val current = state.value
        if (opened && current.canWrite && current.form != null && !current.choosingCategory)
            updateState {
                it.copy(
                    choosingEmployment = true,
                    choices = current.form.preparation,
                    formFailure = null,
                )
            }
    }

    private fun onEmploymentDismissed(event: DocumentFileEvent.EmploymentDismissed) {
        if (!state.value.choosingEmployment) return
        formRequest?.cancel()
        formRequest = null
        updateState {
            it.copy(
                choosingEmployment = false,
                choosingCategory = false,
                choices = null,
                formLoading = false,
                formFailure = null,
            )
        }
    }

    private fun onEmploymentPageRequested(event: DocumentFileEvent.EmploymentPageRequested) {
        val current = state.value
        if (
            !opened ||
                !current.choosingEmployment ||
                current.formLoading ||
                (event.after != null && event.after != current.choices?.employments?.nextCursor)
        )
            return
        loadForm(event.after, choices = true)
        viewModelScope.launch { emitEffect(DocumentFileEffect.ScrollEmploymentsToStart) }
    }

    private fun onEmploymentSelected(event: DocumentFileEvent.EmploymentSelected) {
        val current = state.value
        val choices = current.choices ?: return
        if (
            !opened ||
                !current.choosingEmployment ||
                current.formLoading ||
                choices.employments.items.none { it.id == event.id }
        )
            return
        updateState {
            it.copy(
                form = it.form?.copy(preparation = choices, employmentId = event.id),
                choosingEmployment = false,
                choosingCategory = false,
                choices = null,
                formFailure = null,
            )
        }
    }

    private fun onUploadConfirmed(event: DocumentFileEvent.UploadConfirmed) {
        val current = state.value
        val form = current.form ?: return
        if (!opened || !current.canSubmit || current.choosingEmployment || current.choosingCategory)
            return
        val employment = form.employmentId ?: return
        updateState { it.copy(working = true, failure = null) }
        action =
            viewModelScope.launch {
                try {
                    val result =
                        prepareUpload(
                            target.workspaceId,
                            form.preparation,
                            DocumentUploadInput(
                                employment,
                                form.title,
                                form.classification,
                                form.reason,
                            ),
                            form.metadata.takeIf { form.preparation.employments.metadataSupported },
                        )
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> {
                            reportFailure(result.failure)
                            emitEffect(DocumentFileEffect.ScrollToStart)
                        }
                        is AppResult.Success -> {
                            updateState {
                                it.copy(form = null, choices = null, choosingEmployment = false)
                            }
                            deliver()
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(working = false) }
                }
            }
    }

    private fun onResumeRequested(event: DocumentFileEvent.ResumeRequested) {
        if (!opened || !state.value.canResume) return
        updateState { it.copy(working = true, failure = null) }
        action =
            viewModelScope.launch {
                try {
                    deliver()
                } finally {
                    if (isActive) updateState { it.copy(working = false) }
                }
            }
    }

    private suspend fun deliver() {
        val result = replayUpload(target.workspaceId, target.fileId)
        currentCoroutineContext().ensureActive()
        when (result) {
            is AppResult.Failed -> reportFailure(result.failure)
            is AppResult.Success ->
                result.value.upload?.failure?.let {
                    reportFailure(it)
                    emitEffect(DocumentFileEffect.ScrollToStart)
                }
        }
    }

    private fun onStatusRequested(event: DocumentFileEvent.StatusRequested) {
        if (opened && state.value.canCheck) review(DocumentUploadAction.CheckStatus)
    }

    private fun onValidationRetryRequested(event: DocumentFileEvent.ValidationRetryRequested) {
        if (opened && state.value.canRetryValidation) review(DocumentUploadAction.RetryValidation)
    }

    private fun onCancellationRequested(event: DocumentFileEvent.CancellationRequested) {
        if (opened && state.value.canCancel)
            updateState { it.copy(cancellationReason = "", failure = null) }
    }

    private fun onCancellationDismissed(event: DocumentFileEvent.CancellationDismissed) {
        if (!state.value.working) updateState { it.copy(cancellationReason = null, failure = null) }
    }

    private fun onCancellationReasonChanged(event: DocumentFileEvent.CancellationReasonChanged) {
        if (opened && state.value.cancellationReason != null && !state.value.working)
            updateState { it.copy(cancellationReason = event.value.take(1_000), failure = null) }
    }

    private fun onCancellationConfirmed(event: DocumentFileEvent.CancellationConfirmed) {
        val reason = state.value.cancellationReason ?: return
        if (opened && state.value.canCancel) review(DocumentUploadAction.Cancel(reason))
    }

    private fun review(requested: DocumentUploadAction) {
        updateState { it.copy(working = true, failure = null) }
        action =
            viewModelScope.launch {
                try {
                    val result = reviewUpload(target.workspaceId, target.fileId, requested)
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            updateState { it.copy(cancellationReason = null) }
                            if (result.value.upload?.step !is DocumentUploadStep.Finished) deliver()
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(working = false) }
                }
            }
    }

    private fun reportFailure(failure: Failure) {
        if (documentReadRestricted(failure)) {
            observation?.cancel()
            formRequest?.cancel()
            action?.cancel()
            updateState { DocumentFileState(restriction = failure, loading = false) }
        } else updateState { it.copy(failure = failure) }
    }

    private fun onVerificationRequested(event: DocumentFileEvent.VerificationRequested) {
        if (opened && state.value.needsVerification)
            viewModelScope.launch { emitEffect(DocumentFileEffect.VerifyAccount) }
    }

    private fun onBackRequested(event: DocumentFileEvent.BackRequested) {
        if (!opened) return
        onClosed(DocumentFileEvent.Closed)
        viewModelScope.launch { emitEffect(DocumentFileEffect.Back) }
    }
}
