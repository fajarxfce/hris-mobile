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

/** Policy deadlines prompt revalidation; stale authority never removes a local selection. */
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
                                    val initial =
                                        workspaceContext(
                                            session,
                                            (selected as AppResult.Success).value,
                                            build,
                                            clock.now(),
                                        )
                                    emit(AppResult.Success(initial))
                                    val workspace = initial.workspace ?: return@flow
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
                                            .filter { it > now }
                                            .distinct()
                                            .sorted()
                                    // At most freshness and scheduled maintenance. No polling
                                    // or offline-read expiry is attached to authorization age.
                                    for (deadline in policyDeadlines) {
                                        delay(
                                            (deadline - clock.now())
                                                .inWholeMilliseconds
                                                .coerceAtLeast(0) + 1
                                        )
                                        val current =
                                            workspaceContext(session, workspace, build, clock.now())
                                        emit(AppResult.Success(current))
                                        if (current.workspace == null) return@flow
                                    }
                                }
                            }
                    }
                }
            }
            .distinctUntilChanged()
}
