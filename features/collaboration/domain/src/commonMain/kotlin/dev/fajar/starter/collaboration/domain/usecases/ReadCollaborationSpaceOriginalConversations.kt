package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Finite metadata recovery; success grants no local admission, SDK lease or delivery authority. */
class ReadCollaborationSpaceOriginalConversations(
    private val originals: CollaborationSpaceOriginalConversationRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend fun list(
        owner: CollaborationSpaceOwner,
        after: CollaborationOriginalConversationPosition? = null,
        limit: Int = 30,
    ): AppResult<CollaborationSpaceOriginalConversationPage> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationOriginalConversationQuery(owner, after, limit))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        return readCollaborationSpaceMetadata(
            owner,
            directory,
            sessions,
            server,
            build,
            clock,
            timeSource,
            acquire = {
                when (val acquired = originals.list(owner, after, limit)) {
                    is AppResult.Failed -> acquired
                    is AppResult.Success -> {
                        val page = acquired.value
                        if (page.items.size > limit)
                            collaborationRecoveryFailure(
                                "client.collaboration_invalid_response",
                                FailureKind.Unexpected,
                            )
                        else AppResult.Success(page.copy(items = page.items.toList()))
                    }
                }
            },
            valid = { validCollaborationSpaceOriginalConversationPage(it, owner, after, limit) },
        )
    }

    suspend fun get(
        owner: CollaborationSpaceOwner,
        position: CollaborationOriginalConversationPosition,
    ): AppResult<CollaborationSpaceOriginalConversation> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationOriginalConversationOwner(owner) ||
                !validCollaborationOriginalConversationPosition(position)
        )
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        return readCollaborationSpaceMetadata(
            owner,
            directory,
            sessions,
            server,
            build,
            clock,
            timeSource,
            acquire = { originals.get(owner, position) },
            valid = { validCollaborationSpaceOriginalConversation(it, owner, position) },
        )
    }
}
