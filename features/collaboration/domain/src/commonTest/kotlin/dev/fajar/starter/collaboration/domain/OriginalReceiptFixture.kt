package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.RecoverCollaborationMessageReceipts
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.Flow

internal class OriginalReceiptFixture {
    val delivery = CollaborationDeliveryFixture()
    val base = delivery.base
    val origin = CollaborationMessageOrigin("@fictional:example.test", "a".repeat(64))
    val originals = mutableMapOf<String, CollaborationHistoryMessage>()
    var reads = 0
    var readFailure: Failure? = null
    var beforeReadReturns: suspend () -> Unit = {}

    suspend fun prepare(count: Int = 1) {
        delivery.prepare(count)
        base.records.value =
            delivery.records.map {
                it.copy(
                    state = (it.state as CollaborationMessageState.Staged).copy(origin = origin)
                )
            }
        delivery.records.forEachIndexed { index, record ->
            originals[record.intent.id] =
                CollaborationHistoryMessage(
                    origin.sender,
                    record.intent.id,
                    "\$original-$index",
                    index.toLong(),
                    origin.contentHash,
                    record.intent.text,
                    listOf("\$original-$index"),
                    false,
                )
        }
    }

    val history =
        object : CollaborationHistoryRepository {
            override suspend fun getByLogicalId(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                sender: String,
                logicalId: String,
            ): AppResult<CollaborationHistoryMessage?> {
                assertEquals(base.summary!!.conversation.companyId, access.companyId)
                assertEquals(delivery.conversation, conversationId)
                assertEquals(delivery.device.roomId, roomId)
                assertEquals(origin.sender, sender)
                reads++
                val result =
                    readFailure?.let { AppResult.Failed(it) }
                        ?: AppResult.Success(originals[logicalId])
                beforeReadReturns()
                return result
            }

            override suspend fun getEventFact(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryEventFact?> = error("No alias scan")

            override fun observe(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String?,
                after: String?,
                limit: Int,
            ): Flow<AppResult<CollaborationHistoryPage>> = error("No history scan")

            override suspend fun getByEvent(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("ACK event ID is not known")

            override suspend fun merge(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                changes: List<CollaborationHistoryChange>,
            ): AppResult<Boolean> = error("No history acquisition")
        }
    private val useCase =
        RecoverCollaborationMessageReceipts(
            history,
            delivery.messages,
            delivery.receipts,
            delivery.lists,
            base.setup.sessions,
            base.setup.workspaces,
            base.server,
            base.build,
            base.clock,
            delivery.completions,
        )

    suspend fun recover() = useCase("selection", delivery.conversation)
}
