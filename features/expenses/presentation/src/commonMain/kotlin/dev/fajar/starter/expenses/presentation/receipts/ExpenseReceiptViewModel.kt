package dev.fajar.starter.expenses.presentation.receipts

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.DOCUMENT_EVIDENCE_PERIOD
import dev.fajar.starter.documents.domain.usecases.LoadDocumentEvidence
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlin.time.Clock
import kotlin.time.Duration
import kotlinx.coroutines.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class ExpenseReceiptViewModel(
    @InjectedParam private val target: ExpenseReceiptTarget,
    private val loadEvidence: LoadDocumentEvidence,
    @InjectedParam private val clock: Clock,
) :
    MviViewModel<ExpenseReceiptState, ExpenseReceiptEvent, ExpenseReceiptEffect>(
        ExpenseReceiptState(target.selectedDocumentIds.toSet())
    ) {
    private var foreground = false
    private var acquisition: Job? = null
    private var expiration: Job? = null

    init {
        on<ExpenseReceiptEvent.Entered>(::onEntered)
        on<ExpenseReceiptEvent.Stopped>(::onStopped)
        on<ExpenseReceiptEvent.ReloadRequested>(::onReloadRequested)
        on<ExpenseReceiptEvent.PageRequested>(::onPageRequested)
        on<ExpenseReceiptEvent.Selected>(::onSelected)
        on<ExpenseReceiptEvent.Expired>(::onExpired)
        on<ExpenseReceiptEvent.Dismissed>(::onDismissed)
        on<ExpenseReceiptEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onEntered(event: ExpenseReceiptEvent.Entered) {
        if (foreground || state.value.closing) return
        foreground = true
        loadPage(null)
    }

    private fun onStopped(event: ExpenseReceiptEvent.Stopped) {
        foreground = false
        acquisition?.cancel()
        acquisition = null
        expiration?.cancel()
        expiration = null
        updateState { it.copy(snapshot = null, after = null, loading = false) }
    }

    private fun onReloadRequested(event: ExpenseReceiptEvent.ReloadRequested) {
        if (!foreground || state.value.loading || state.value.closing) return
        loadPage(state.value.after)
    }

    private fun onPageRequested(event: ExpenseReceiptEvent.PageRequested) {
        val current = state.value
        if (
            !foreground ||
                current.loading ||
                current.closing ||
                current.restriction != null ||
                event.after == current.after ||
                (event.after != null && event.after != current.snapshot?.page?.nextCursor)
        )
            return
        loadPage(event.after)
    }

    private fun loadPage(after: String?) {
        acquisition?.cancel()
        val retained =
            state.value.snapshot?.takeIf {
                it.query.after == after && state.value.restriction == null
            }
        if (retained == null) expiration?.cancel()
        updateState { it.copy(snapshot = retained, after = after, loading = true, failure = null) }
        acquisition =
            viewModelScope.launch {
                try {
                    val result =
                        loadEvidence(
                            target.workspaceId,
                            DocumentEvidenceQuery(
                                target.employeeId,
                                DocumentClassification.RECEIPT,
                                after,
                            ),
                        )
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            expiration?.cancel()
                            updateState {
                                it.copy(snapshot = result.value, restriction = null, failure = null)
                            }
                            expiration =
                                viewModelScope.launch {
                                    delay(
                                        (result.value.expiresAt - clock.now()).coerceIn(
                                            Duration.ZERO,
                                            DOCUMENT_EVIDENCE_PERIOD,
                                        )
                                    )
                                    onEvent(ExpenseReceiptEvent.Expired(result.value))
                                }
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(loading = false) }
                }
            }
    }

    private fun reportFailure(failure: Failure) {
        val restricted =
            failure.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied) ||
                failure.code in workspaceAccessLossCodes ||
                failure.code in
                    setOf(
                        "company_module_disabled",
                        "company_maintenance",
                        "client_update_required",
                        "client_version_required",
                        "client.company_policy_expired",
                        "client.company_policy_unavailable",
                    )
        if (restricted) expiration?.cancel()
        updateState {
            if (restricted) it.copy(snapshot = null, restriction = failure, failure = null)
            else it.copy(failure = failure)
        }
        if (failure.code in workspaceAccessLossCodes && !state.value.needsVerification)
            closePicker(ExpenseReceiptEffect.RecheckWorkspace)
    }

    private fun onSelected(event: ExpenseReceiptEvent.Selected) {
        val current = state.value
        if (!foreground || current.loading || current.closing || current.restriction != null) return
        val snapshot = current.snapshot ?: return
        if (snapshot.expiresAt <= clock.now()) {
            onEvent(ExpenseReceiptEvent.Expired(snapshot))
            return
        }
        val document =
            snapshot.page.items.singleOrNull { it.revisionId == event.revisionId } ?: return
        if (document.documentId in current.selectedDocumentIds) return
        closePicker(ExpenseReceiptEffect.Selected(document))
    }

    private fun onExpired(event: ExpenseReceiptEvent.Expired) {
        if (!foreground || state.value.snapshot != event.snapshot) return
        updateState {
            it.copy(
                snapshot = null,
                failure = null,
                restriction =
                    Failure(
                        FailureKind.AccessDenied,
                        "Refresh the document list before selecting an attachment.",
                        code = "client.document_evidence_expired",
                    ),
            )
        }
    }

    private fun onDismissed(event: ExpenseReceiptEvent.Dismissed) {
        if (!state.value.closing) closePicker(ExpenseReceiptEffect.Dismiss)
    }

    private fun onVerificationRequested(event: ExpenseReceiptEvent.VerificationRequested) {
        if (state.value.needsVerification && !state.value.closing)
            closePicker(ExpenseReceiptEffect.VerifyAccount)
    }

    private fun closePicker(effect: ExpenseReceiptEffect) {
        foreground = false
        acquisition?.cancel()
        expiration?.cancel()
        updateState { it.copy(closing = true, loading = false, snapshot = null) }
        viewModelScope.launch { emitEffect(effect) }
    }
}
