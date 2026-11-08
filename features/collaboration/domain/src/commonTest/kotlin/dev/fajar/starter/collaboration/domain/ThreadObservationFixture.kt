package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationThread
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.*

internal class ThreadObservationFixture {
    val history = HistoryObserverFixture()
    val owner = history.owner
    val conversation = history.conversation
    val access =
        CollaborationLocalAccess(
            owner.setup.session!!.id,
            owner.setup.session!!.binding.audience,
            owner.setup.session!!.user.id,
            owner.setup.workspace!!.company.id,
        )
    var self: CollaborationSelfIdentity? = CollaborationSelfIdentity("@fictional:example.test")
    var beforeIdentity: suspend () -> Unit = {}
    var beforeCommands: suspend () -> Unit = {}
    var identityFailure: Failure? = null
    var commandFailure: Failure? = null
    var identityReads = 0
    var commandsStarted = 0
    private val identities =
        object : CollaborationIdentityRepository {
            override suspend fun current(
                access: CollaborationLocalAccess
            ): AppResult<CollaborationSelfIdentity?> {
                identityReads++
                beforeIdentity()
                return identityFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(self)
            }

            override suspend fun retain(
                access: CollaborationLocalAccess,
                identity: CollaborationSelfIdentity,
            ): AppResult<Boolean> = error("No identity mutation")
        }
    private val messages =
        object : CollaborationMessageRepository by owner.messages {
            override fun observe(access: CollaborationLocalAccess, conversationId: String?) = flow {
                commandsStarted++
                beforeCommands()
                val failure = commandFailure
                if (failure != null) emit(AppResult.Failed(failure))
                else emitAll(owner.messages.observe(access, conversationId))
            }
        }
    val recipientObservations = RecipientObservationFixture()
    val observer =
        ObserveCollaborationThread(
            history.history,
            messages,
            owner.lists,
            identities,
            owner.setup.sessions,
            owner.setup.workspaces,
            owner.server,
            owner.build,
            owner.clock,
            recipientObservations,
        )

    fun observe(
        expected: CollaborationLocalAccess = access,
        window: CollaborationHistoryWindow = CollaborationHistoryWindow(),
    ) = observer("selection", expected, conversation, window)
}
