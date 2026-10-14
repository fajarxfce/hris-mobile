package dev.fajar.starter.people.domain.profile.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.people.domain.profile.entities.*
import dev.fajar.starter.people.domain.profile.policies.*
import dev.fajar.starter.people.domain.profile.repositories.SelfProfileReplicaRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ObserveSelfProfiles(
    private val replicas: SelfProfileReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        selection: Flow<String?>,
    ): Flow<AppResult<SelfProfileView?>> =
        sessions
            .observe()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success -> {
                        val session = result.value
                        if (session == null)
                            flowOf(selfProfileFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { current ->
                                when (current) {
                                    is AppResult.Failed -> current
                                    is AppResult.Success ->
                                        admitSelfProfile(
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
                                                    if (
                                                        value.checkpoint != null &&
                                                            !sameStoredSelfProfileScope(
                                                                access,
                                                                value.checkpoint.access,
                                                            )
                                                    )
                                                        selfProfileFailure(
                                                            "client.self_profile_scope_changed",
                                                            FailureKind.AccessDenied,
                                                        )
                                                    else
                                                        AppResult.Success(
                                                            selectSelfProfile(value, selected)
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
