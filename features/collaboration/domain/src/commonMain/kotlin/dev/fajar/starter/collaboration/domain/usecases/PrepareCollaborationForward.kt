@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.withTimeoutOrNull

/** Creates stable outgoing identities from admitted local originals; never touches a draft/SDK. */
class PrepareCollaborationForward(
    private val quotes: CollaborationQuoteRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        workspaceId: String,
        expected: CollaborationLocalAccess,
        selection: CollaborationForwardSelection,
    ): AppResult<CollaborationForwardBatch> {
        val detached = selection.detached()
        if (!validCollaborationForwardSelection(detached))
            return forwardUnavailable(FailureKind.Validation)
        return withTimeoutOrNull(8_000) {
            val access =
                CollaborationForwardAccess(
                    workspaceId,
                    expected,
                    quotes,
                    lists,
                    sessions,
                    workspaces,
                    server,
                    build,
                    clock,
                )
            val checked = access.verify(detached)
            if (checked is AppResult.Failed) return@withTimeoutOrNull checked
            val createdAt = clock.now()
            val intents =
                detached.destinationIds.flatMap { target ->
                    detached.messages.mapIndexed { index, source ->
                        CollaborationMessageIntent(
                            Uuid.random().toString(),
                            expected.messageOwner(),
                            target,
                            createdAt + index.milliseconds,
                            source.text,
                            forwarded = true,
                        )
                    }
                }
            val batch = CollaborationForwardBatch(expected, detached, intents)
            if (!validCollaborationForwardBatch(batch)) forwardUnavailable(FailureKind.Validation)
            else AppResult.Success(batch)
        } ?: forwardUnavailable(FailureKind.Timeout)
    }
}
