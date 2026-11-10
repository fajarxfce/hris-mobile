package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flow

/** Independent namespace with observable CAS and journal response-loss boundaries. */
internal class SpaceDeliveryStorageFixture(private val f: SpaceOriginalDeliveryFixture) {
    val access = f.queue.owner.localDirectoryAccess()
    val records = linkedMapOf<String, CollaborationSpaceMessageRecord>()
    val archived = linkedMapOf<String, CollaborationSpaceMessageCompletion>()
    var journal = emptyList<CollaborationSpaceMessageReceipt>()
    var commitUpdate = true
    var updateResponse: AppResult<Boolean> = AppResult.Success(true)
    var commitAppend = true
    var appendResponse: AppResult<Boolean> = AppResult.Success(true)

    val messages =
        object : CollaborationSpaceMessageRepository {
            override fun observe(access: CollaborationSpaceLocalAccess, conversationId: String?) =
                flow {
                    assertEquals(this@SpaceDeliveryStorageFixture.access, access)
                    assertEquals(f.model.conversation.id, conversationId)
                    emit(
                        f.read("space-observe") {
                            records.values.filterNot { it.intent.id in archived }
                        }
                    )
                }

            override fun observeRecord(access: CollaborationSpaceLocalAccess, id: String) = flow {
                emit(get(access, id))
            }

            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageRecord?> {
                assertEquals(this@SpaceDeliveryStorageFixture.access, access)
                return f.read("space-get") { records[id] }
            }

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                intent: CollaborationSpaceMessageIntent,
            ): AppResult<CollaborationMessageInsertion> = error("Delivery never authors an intent")

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceMessageRecord,
                state: CollaborationMessageState,
            ): AppResult<Boolean> {
                assertEquals(this@SpaceDeliveryStorageFixture.access, access)
                val entered = f.read("space-update") { Unit }
                if (entered is AppResult.Failed) return entered
                assertEquals(expected, records[expected.intent.id])
                if (commitUpdate)
                    records[expected.intent.id] =
                        expected.copy(revision = expected.revision + 1, state = state)
                f.hooks["space-after-update"]?.invoke()
                return updateResponse
            }
        }
    val receipts =
        object : CollaborationSpaceReceiptRepository {
            override suspend fun read(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<List<CollaborationSpaceMessageReceipt>> {
                assertEquals(this@SpaceDeliveryStorageFixture.access, access)
                assertEquals(f.model.conversation.id, conversationId)
                return f.read("space-journal") { journal }
            }

            override suspend fun append(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                receipts: List<CollaborationSpaceMessageReceipt>,
            ): AppResult<Boolean> {
                assertEquals(this@SpaceDeliveryStorageFixture.access, access)
                assertEquals(f.model.conversation.id, conversationId)
                check(receipts.size in 1..32)
                val entered = f.read("space-append") { Unit }
                if (entered is AppResult.Failed) return entered
                if (commitAppend) {
                    val next = journal.associateBy { it.intent.id }.toMutableMap()
                    for (receipt in receipts) next[receipt.intent.id] =
                        receipt.copy(
                            eventIds =
                                (next[receipt.intent.id]?.eventIds.orEmpty() + receipt.eventIds)
                                    .distinct()
                        )
                    journal = next.values.toList()
                }
                return appendResponse
            }
        }
    val completions =
        object : CollaborationSpaceMessageCompletionRepository {
            override suspend fun readCompletion(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageCompletion?> {
                assertEquals(this@SpaceDeliveryStorageFixture.access, access)
                return f.read("space-completion") { archived[id] }
            }

            override suspend fun archiveCompletion(
                access: CollaborationSpaceLocalAccess,
                completion: CollaborationSpaceMessageCompletion,
            ): AppResult<Boolean> = error("Separate retirement")
        }
}
