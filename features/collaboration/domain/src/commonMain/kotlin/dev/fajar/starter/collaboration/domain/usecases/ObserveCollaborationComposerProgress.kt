package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Owned local originals and their exact records; absence never implies completion. */
class ObserveCollaborationComposerProgress(
    private val progress: CollaborationMessageProgressRepository,
    private val outbox: CollaborationComposerOutboxRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        expected: CollaborationLocalAccess,
        originals: Flow<List<CollaborationComposerSubmission>> = flowOf(emptyList()),
    ): Flow<AppResult<List<CollaborationComposerMessageProgress>>> =
        sessions
            .observeMessageAccess(workspaceId, workspaces, server, build, clock)
            .flatMapLatest { captured ->
                when (captured) {
                    is AppResult.Failed -> flowOf(captured)
                    is AppResult.Success ->
                        if (captured.value != expected)
                            flowOf(
                                collaborationSetupFailure(
                                    "client.session_changed",
                                    FailureKind.Unauthorized,
                                )
                            )
                        else
                            lists.observeAdmission(expected).flatMapLatest { gate ->
                                when (gate) {
                                    is AppResult.Failed -> flowOf(gate)
                                    is AppResult.Success ->
                                        gate.value.denial?.let { flowOf(AppResult.Failed(it)) }
                                            ?: combine(
                                                    originals
                                                        .map { it.toList() }
                                                        .distinctUntilChanged(),
                                                    outbox.observe(expected),
                                                ) { requested, retained ->
                                                    when (retained) {
                                                        is AppResult.Failed -> retained
                                                        is AppResult.Success ->
                                                            inventory(requested, retained.value)
                                                    }
                                                }
                                                .distinctUntilChanged()
                                                .flatMapLatest { inventory ->
                                                    when (inventory) {
                                                        is AppResult.Failed -> flowOf(inventory)
                                                        is AppResult.Success ->
                                                            observeRequested(
                                                                workspaceId,
                                                                expected,
                                                                inventory.value,
                                                            )
                                                    }
                                                }
                                }
                            }
                }
            }
            .buffer(0)
            .distinctUntilChanged()

    private fun inventory(
        requested: List<CollaborationComposerSubmission>,
        entries: List<CollaborationComposerOutboxEntry>,
    ): AppResult<List<CollaborationComposerMessageProgress>> {
        val retained = entries.toList()
        if (
            requested.size > 128 ||
                retained.size > 128 ||
                requested.any { !validCollaborationComposerSubmission(it) } ||
                retained.any { !validCollaborationComposerSubmission(it.submission) } ||
                requested.map { it.id }.distinct().size != requested.size ||
                retained.map { it.submission.id }.distinct().size != retained.size
        )
            return invalidDraft()
        val byId = retained.associateBy { it.submission.id }
        if (
            requested.any { original ->
                byId[original.id]?.submission?.let { it != original } == true
            }
        )
            return collaborationSetupFailure(
                "client.collaboration_composer_changed",
                FailureKind.Storage,
            )
        return AppResult.Success(
            (requested + retained.map { it.submission })
                .distinctBy { it.id }
                .map { original ->
                    val entry = byId[original.id]
                    CollaborationComposerMessageProgress(
                        original,
                        null,
                        retained = entry != null,
                        reviewRequired = entry?.reviewRequired == true,
                    )
                }
        )
    }

    private fun observeRequested(
        workspaceId: String,
        access: CollaborationLocalAccess,
        originals: List<CollaborationComposerMessageProgress>,
    ): Flow<AppResult<List<CollaborationComposerMessageProgress>>> {
        val observed: Flow<AppResult<List<CollaborationComposerMessageProgress>>> =
            if (originals.isEmpty()) flowOf(AppResult.Success(emptyList()))
            else
                combine(
                    originals.map { original ->
                        progress.observeRecord(access, original.original.id).map { read ->
                            when (read) {
                                is AppResult.Failed -> read
                                is AppResult.Success -> {
                                    val record = read.value
                                    if (
                                        record != null &&
                                            record.intent !=
                                                collaborationComposerIntent(
                                                    access,
                                                    original.original,
                                                )
                                    )
                                        collaborationSetupFailure(
                                            "client.collaboration_composer_changed",
                                            FailureKind.Storage,
                                        )
                                    else {
                                        val detached =
                                            if (record?.state is CollaborationMessageState.Staging)
                                                record.copy(
                                                    state =
                                                        record.state.copy(
                                                            before = record.state.before.toSet()
                                                        )
                                                )
                                            else record
                                        AppResult.Success(original.copy(record = detached))
                                    }
                                }
                            }
                        }
                    }
                ) { values ->
                    values.firstOrNull { it is AppResult.Failed } as? AppResult.Failed
                        ?: AppResult.Success(values.map { (it as AppResult.Success).value })
                }
        return observed
            .map { result ->
                val guard =
                    sessions.checkDraftContinuation(
                        workspaceId,
                        access,
                        lists,
                        workspaces,
                        server,
                        build,
                        clock,
                    )
                if (guard is AppResult.Failed) guard else result
            }
            .buffer(0)
            .distinctUntilChanged()
    }
}
