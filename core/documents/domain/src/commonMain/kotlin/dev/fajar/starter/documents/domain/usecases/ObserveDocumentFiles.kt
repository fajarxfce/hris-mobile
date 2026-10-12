package dev.fajar.starter.documents.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.repositories.DocumentFileRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ObserveDocumentFiles(
    private val files: DocumentFileRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(workspaceId: String): Flow<AppResult<DocumentLocalFiles>> =
        sessions
            .observe()
            .flatMapLatest { identity ->
                when (identity) {
                    is AppResult.Failed -> flowOf(identity)
                    is AppResult.Success -> {
                        val session = identity.value
                        if (session == null)
                            flowOf(documentFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { selected ->
                                when (selected) {
                                    is AppResult.Failed -> selected
                                    is AppResult.Success ->
                                        admitDocumentCatalog(
                                            workspaceId,
                                            selected.value,
                                            session,
                                            build,
                                            clock.now(),
                                        )
                                }
                            }
                    }
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { admitted ->
                when (admitted) {
                    is AppResult.Failed -> flowOf(admitted)
                    is AppResult.Success ->
                        flow<AppResult<DocumentLocalFiles>> {
                            val access = admitted.value
                            val deadline = minOf(access.readUntil, access.sessionExpiresAt)
                            val expiry =
                                documentFailure(
                                    if (access.sessionExpiresAt <= access.readUntil)
                                        "session_revoked"
                                    else access.expiryCode,
                                    FailureKind.AccessDenied,
                                )
                            val remaining = deadline - clock.now()
                            if (remaining.isPositive()) {
                                withTimeoutOrNull(remaining) {
                                    files.observe(access).collect { result ->
                                        currentCoroutineContext().ensureActive()
                                        if (clock.now() >= deadline) emit(expiry)
                                        else
                                            emit(
                                                when (result) {
                                                    is AppResult.Failed -> result
                                                    is AppResult.Success ->
                                                        AppResult.Success(
                                                            DocumentLocalFiles(
                                                                access,
                                                                result.value.toList(),
                                                            )
                                                        )
                                                }
                                            )
                                    }
                                    // A completed storage stream cannot extend the read lease.
                                    awaitCancellation()
                                }
                            }
                            emit(expiry)
                        }
                }
            }
            .distinctUntilChanged()
}
