package dev.fajar.starter.app.bootstrap

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceLocalAccess
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.observability.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One process-owned local inbox observer; account replacement cancels and joins the old repair. */
class ApplicationCollaborationInboxOwner(
    private val observe: () -> Flow<AppResult<CollaborationSpaceLocalAccess?>>,
    private val repair: (CollaborationSpaceLocalAccess) -> Flow<AppResult<Unit>>,
) {
    private val collection = Mutex()

    /** The application lifetime owns this suspension and its cancellation; no native dependency. */
    suspend fun collect() {
        collection.withLock {
            observe()
                .onCompletion { cause -> if (cause == null) emit(AppResult.Success(null)) }
                .distinctUntilChanged()
                .collectLatest { result ->
                    val access = (result as? AppResult.Success)?.value ?: return@collectLatest
                    try {
                        repair(access).collect { outcome ->
                            if (outcome is AppResult.Failed) {
                                Diagnostics.record(
                                    Diagnostic(
                                        DiagnosticArea.Application,
                                        DiagnosticKind.OperationFailed,
                                        "ChatInboxRepair_${outcome.failure.kind.name}",
                                    )
                                )
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A stopped child requires a new owner or an explicit process restart.
                        Diagnostics.record(
                            Diagnostic(
                                DiagnosticArea.Application,
                                DiagnosticKind.OperationFailed,
                                "ChatInboxRepairStopped",
                            )
                        )
                    }
                }
        }
    }
}
