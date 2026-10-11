package dev.fajar.starter.workspace.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

/** Each snapshot has finite deadlines; failed refreshes cannot cause a polling loop. */
class ObserveWorkspaceContext(
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val build: AppBuild,
    private val clock: Clock = Clock.System,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<AppResult<WorkspaceContext?>> =
        sessions
            .observe()
            .flatMapLatest { identity ->
                when (identity) {
                    is AppResult.Failed -> flowOf(identity)
                    is AppResult.Success -> {
                        val session = identity.value
                        if (session == null) flowOf(AppResult.Success(null))
                        else
                            workspaces.observe(session.id).flatMapLatest { selected ->
                                flow<AppResult<WorkspaceContext?>> {
                                    if (selected is AppResult.Failed) {
                                        emit(selected)
                                        return@flow
                                    }
                                    val workspace = (selected as AppResult.Success).value
                                    val unselected =
                                        AppResult.Success(WorkspaceContext(session.id, null, null))
                                    if (
                                        workspace == null ||
                                            validateWorkspaceOwnership(
                                                workspace.id,
                                                workspace,
                                                session,
                                                clock.now(),
                                            ) is
                                                AppResult.Failed
                                    ) {
                                        emit(unselected)
                                        return@flow
                                    }
                                    emit(
                                        AppResult.Success(
                                            workspaceContext(workspace, build, clock.now())
                                        )
                                    )
                                    val snapshot = workspace.policy
                                    val now = clock.now()
                                    val policyDeadlines =
                                        listOfNotNull(
                                                snapshot?.let(::companyPolicyDeadline),
                                                snapshot?.policy?.maintenance?.let { window ->
                                                    snapshot.requestedAt +
                                                        (window.startsAt -
                                                            snapshot.policy.serverTime)
                                                },
                                            )
                                            .filter {
                                                it > now && it < workspace.admission.expiresAt
                                            }
                                            .distinct()
                                            .sorted()
                                    // At most freshness, scheduled maintenance, and admission
                                    // expiry. Timers cross fractional server deadlines before
                                    // evaluating their state, without polling after failure.
                                    for (deadline in
                                        policyDeadlines + workspace.admission.expiresAt) {
                                        delay(
                                            (deadline - clock.now())
                                                .inWholeMilliseconds
                                                .coerceAtLeast(0) + 1
                                        )
                                        if (workspace.admission.expiresAt <= clock.now()) {
                                            emit(unselected)
                                            return@flow
                                        }
                                        emit(
                                            AppResult.Success(
                                                workspaceContext(workspace, build, clock.now())
                                            )
                                        )
                                    }
                                }
                            }
                    }
                }
            }
            .distinctUntilChanged()
}
