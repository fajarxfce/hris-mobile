package dev.fajar.starter.people.domain.replica.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.people.domain.replica.entities.*
import dev.fajar.starter.people.domain.replica.policies.*
import dev.fajar.starter.people.domain.replica.repositories.DirectoryReplicaRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ObserveCompanyDirectory(
    private val replicas: DirectoryReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        selection: Flow<DirectorySelection>,
    ): Flow<AppResult<DirectoryView?>> =
        sessions
            .observe()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success -> {
                        val session = result.value
                        if (session == null)
                            flowOf(directoryFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { current ->
                                when (current) {
                                    is AppResult.Failed -> current
                                    is AppResult.Success ->
                                        admitDirectory(
                                            workspaceId,
                                            current.value,
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
                    is AppResult.Success -> {
                        val access = admitted.value
                        replicas.observeAdmission(access).flatMapLatest { admission ->
                            when (admission) {
                                is AppResult.Failed -> flowOf(admission)
                                is AppResult.Success -> {
                                    val denial = admission.value.denial
                                    if (denial != null) flowOf(AppResult.Failed(denial))
                                    else
                                        combine(
                                            replicas.observe(access),
                                            selection.distinctUntilChanged(),
                                        ) { snapshot, selected ->
                                            currentCoroutineContext().ensureActive()
                                            when (snapshot) {
                                                is AppResult.Failed -> snapshot
                                                is AppResult.Success -> {
                                                    val value = snapshot.value
                                                    val validation =
                                                        validateDirectorySelection(selected)
                                                    if (validation is AppResult.Failed) validation
                                                    else if (
                                                        value.checkpoint != null &&
                                                            !sameStoredDirectoryScope(
                                                                access,
                                                                value.checkpoint.access,
                                                            )
                                                    )
                                                        directoryFailure(
                                                            "client.directory_scope_changed",
                                                            FailureKind.AccessDenied,
                                                        )
                                                    else
                                                        AppResult.Success(
                                                            selectDirectory(value, selected)
                                                        )
                                                }
                                            }
                                        }
                                }
                            }
                        }
                    }
                }
            }
            .distinctUntilChanged()
}
