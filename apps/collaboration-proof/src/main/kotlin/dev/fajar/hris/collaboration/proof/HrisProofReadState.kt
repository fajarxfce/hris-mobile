package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*

/**
 * Fictional native owners: real read-state store, local mark policy and admitted receipt replay.
 */
internal class HrisProofReadState(
    private val runtime: AndroidCollaborationRuntimeLease,
    private val conversationId: String,
    private val roomId: String,
    private val states: () -> CollaborationReadStateRepository,
    private val history: () -> CollaborationHistoryRepository,
    private val lists: () -> CollaborationListRepository,
    private val app: HrisProofApplicationAccess,
    private val reopen: suspend () -> Unit,
) {
    private val access = runtime.owner.localListAccess()

    suspend fun prepare(sentEventId: String, replyEventId: String) {
        val repository = lists()
        val summary = success(app.conversations.summary(runtime.owner, conversationId))
        check(summary.conversation.id == conversationId)
        val checkpoint = success(repository.checkpoint(access))
        check(success(repository.merge(access, checkpoint, listOf(summary), app.clock.now())))
        val intent = intent(replyEventId)
        val events = listOf(sentEventId, replyEventId)
        val mark =
            MarkCollaborationMessagesRead(
                states(),
                history(),
                repository,
                app.sessions,
                app.workspaces,
                app.server,
                app.build,
                app.clock,
            )
        val marked =
            success(
                mark(
                    CollaborationLocalScope.Captured(access),
                    conversationId,
                    roomId,
                    events,
                    intent,
                    access,
                )
            )
        check(
            marked.pending == listOf(intent) &&
                marked.readEventIds == events.toSet() &&
                marked.acknowledged == null
        )
        reopen()
        check(read(events) == marked)
        var reachedAck = false
        val interrupted =
            object : CollaborationReadStateRepository by states() {
                override suspend fun acknowledge(
                    access: CollaborationLocalAccess,
                    conversationId: String,
                    roomId: String,
                    intent: CollaborationReadReceiptIntent,
                ): AppResult<Boolean> {
                    check(
                        access == this@HrisProofReadState.access &&
                            conversationId == this@HrisProofReadState.conversationId &&
                            roomId == this@HrisProofReadState.roomId
                    )
                    check(intent == this@HrisProofReadState.intent(replyEventId))
                    reachedAck = true
                    return AppResult.Failed(
                        Failure(
                            FailureKind.Storage,
                            "Fixture acknowledgement interrupted.",
                            code = "fixture.read_ack_interrupted",
                        )
                    )
                }
            }
        val result = deliver(interrupted)
        check(result is AppResult.Failed)
        if (result.failure.code != "fixture.read_ack_interrupted")
            throw HrisProofFailure(result.failure)
        check(reachedAck)
        reopen()
        check(read(events) == marked)
        // The driver's next actual OS death must leave this original intent pending.
    }

    suspend fun recover(sentEventId: String, replyEventId: String) {
        val events = listOf(sentEventId, replyEventId)
        val before = read(events)
        check(
            before.readEventIds == events.toSet() &&
                before.pending == listOf(intent(replyEventId)) &&
                before.acknowledged == null
        )
        check(success(deliver(states())) == CollaborationReadReceiptDelivery(1, 0))
        val after = read(events)
        check(
            after.readEventIds == before.readEventIds &&
                after.pending.isEmpty() &&
                after.acknowledged == intent(replyEventId)
        )
        val beforeVersion = requireNotNull(before.version)
        val afterVersion = requireNotNull(after.version)
        check(
            afterVersion.generation == beforeVersion.generation &&
                afterVersion.revision > beforeVersion.revision
        )
        reopen()
        check(read(events) == after)
        check(success(deliver(states())) == CollaborationReadReceiptDelivery(0, 0))
        check(read(events) == after)
    }

    private suspend fun read(events: List<String>): CollaborationReadState =
        success(
            ReadCollaborationReadState(
                states(),
                lists(),
                app.sessions,
                app.workspaces,
                app.server,
                app.build,
                app.clock,
            )(CollaborationLocalScope.Captured(access), conversationId, roomId, events)
        )

    private suspend fun deliver(repository: CollaborationReadStateRepository) =
        DeliverCollaborationReadReceipts(
            repository,
            history(),
            lists(),
            app.conversations,
            app.setup,
            HrisProofRuntimeAccess(runtime),
            app.sessions,
            app.workspaces,
            app.server,
            app.build,
            app.clock,
        )(
            CollaborationOperationScope.Selected(app.workspaceId),
            runtime.deviceId,
            conversationId,
            roomId,
        )

    private fun intent(replyEventId: String) =
        CollaborationReadReceiptIntent(
            "c6000000-0000-4000-8000-000000000070",
            replyEventId,
            CollaborationReadReceiptVisibility.Private,
        )

    private fun <T> success(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
