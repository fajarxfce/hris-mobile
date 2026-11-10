package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.DeliverCollaborationSpaceOriginalMessages
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.*

internal class SpaceOriginalDeliveryFixture {
    val directory = SpaceDirectoryUseCaseFixture()
    val native = directory.native
    val model = OriginalTransportFixture(native)
    val queue = model.queue
    val access =
        CollaborationLocalAccess(
            queue.owner.parent.sessionId,
            queue.binding.audience,
            queue.binding.accountId,
            queue.binding.companyId,
        )
    val records = linkedMapOf(queue.record.intent.id to queue.record)
    val archived = mutableMapOf<String, CollaborationMessageCompletion>()
    var journal = emptyList<CollaborationMessageReceipt>()
    var snapshot = queue.snapshot
    var retained: CollaborationSpaceRoomBinding? = model.binding
    var observed = model.transport.copy(recipientReady = true)
    var open = true
    var commitUpdate = true
    var updateResponse: AppResult<Boolean> = AppResult.Success(true)
    var removeSettled = true
    var settlement = CollaborationQueueSettlement.Settled
    val calls = mutableListOf<String>()
    val hooks = mutableMapOf<String, suspend () -> Unit>()
    val failures = mutableMapOf<String, Failure>()
    var onResume:
        suspend (suspend (CollaborationQueueDelivery) -> AppResult<Unit>) -> AppResult<
                CollaborationQueueDelivery
            > =
        { observer ->
            val delivered = delivery()
            snapshot = snapshot.copy(original = snapshot.original.copy(messages = emptyList()))
            when (val saved = observer(delivered)) {
                is AppResult.Failed -> saved
                is AppResult.Success -> AppResult.Success(delivered)
            }
        }

    suspend fun <T> read(stage: String, value: () -> T): AppResult<T> {
        calls += stage
        hooks[stage]?.invoke()
        return failures[stage]?.let { AppResult.Failed(it) } ?: AppResult.Success(value())
    }

    val bindings =
        object : CollaborationSpaceRoomBindingRepository {
            override suspend fun getByConversation(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceRoomBinding?> {
                assertEquals(queue.owner.localDirectoryAccess(), access)
                assertEquals(model.conversation.id, conversationId)
                return read("binding") { retained }
            }

            override suspend fun findByRoom(
                access: CollaborationSpaceLocalAccess,
                serverName: String,
                roomId: String,
            ): AppResult<CollaborationSpaceRoomBinding?> = error("No discovery")

            override suspend fun retain(
                access: CollaborationSpaceLocalAccess,
                binding: CollaborationSpaceRoomBinding,
            ): AppResult<Boolean> = error("No setup")
        }
    val transport =
        object : CollaborationSpaceDirectTransportRepository {
            override suspend fun read(
                conversation: CollaborationSpaceConversation
            ): AppResult<CollaborationSpaceDirectTransport> {
                assertEquals(model.conversation, conversation)
                return read("transport") { observed }
            }
        }
    val lease =
        object : CollaborationSpaceOriginalQueueLease {
            override val runtime = queue.runtime
            override val binding = queue.binding
            override val device = queue.device

            override suspend fun snapshot() =
                read("snapshot") { this@SpaceOriginalDeliveryFixture.snapshot }

            override suspend fun settleConfirmed(
                original: CollaborationQueuedOriginal,
                eventId: String,
            ): AppResult<CollaborationQueueSettlement> {
                assertEquals(queue.original, original)
                assertEquals("\$accepted", eventId)
                return read("settle") {
                    if (removeSettled)
                        snapshot =
                            snapshot.copy(
                                original =
                                    snapshot.original.copy(
                                        messages =
                                            snapshot.original.messages.filterNot { it == original }
                                    )
                            )
                    settlement
                }
            }

            override suspend fun resume(
                expected: CollaborationSpaceOriginalQueueSnapshot,
                onObserved: suspend (CollaborationQueueDelivery) -> AppResult<Unit>,
            ): AppResult<CollaborationQueueDelivery> {
                assertEquals(snapshot, expected)
                val entered = read("resume") { Unit }
                if (entered is AppResult.Failed) return entered
                return onResume(onObserved)
            }

            override suspend fun close() = read("close") { Unit }
        }
    val runtimes =
        object : CollaborationSpaceRuntimeRepository {
            override suspend fun acquireOriginalQueue(
                runtime: CollaborationSpaceRuntimeReady,
                matrixRoomId: String,
            ): AppResult<CollaborationSpaceOriginalQueueLease> {
                assertEquals(queue.runtime, runtime)
                assertEquals(queue.device.roomId, matrixRoomId)
                return read("acquire") { lease }
            }

            override suspend fun inspectOriginalQueue(
                runtime: CollaborationSpaceRuntimeReady,
                matrixRoomId: String,
            ): AppResult<CollaborationSpaceOriginalQueueSnapshot> = error("Only owned lease")

            override suspend fun isSpaceOpen(
                owner: CollaborationSpaceOwner,
                deviceId: String,
                operationId: String,
            ): AppResult<Boolean> {
                assertEquals(queue.owner, owner)
                assertEquals(queue.runtime.deviceId, deviceId)
                assertEquals(queue.runtime.operationId, operationId)
                return read("open") { open }
            }

            override suspend fun installOriginal(
                owner: CollaborationSpaceOwner,
                original: CollaborationExistingRuntimeHandover,
                setup: CollaborationSpaceSetupRecord,
            ): AppResult<CollaborationSpaceRuntimeLease> = error("No install")

            override suspend fun closeSpace(owner: CollaborationSpaceOwner) =
                read("closeSpace") { Unit }
        }
    val messages =
        object : CollaborationMessageRepository {
            override fun observe(access: CollaborationLocalAccess, conversationId: String?) = flow {
                assertEquals(this@SpaceOriginalDeliveryFixture.access, access)
                assertEquals(queue.position.conversationId, conversationId)
                emit(read("observe") { records.values.filterNot { it.intent.id in archived } })
            }

            override suspend fun get(
                access: CollaborationLocalAccess,
                id: String,
            ): AppResult<CollaborationMessageRecord?> {
                assertEquals(this@SpaceOriginalDeliveryFixture.access, access)
                return read("get") { records[id] }
            }

            override suspend fun insert(
                access: CollaborationLocalAccess,
                intent: CollaborationMessageIntent,
            ): AppResult<CollaborationMessageInsertion> = error("Never create or resend")

            override suspend fun update(
                access: CollaborationLocalAccess,
                expected: CollaborationMessageRecord,
                state: CollaborationMessageState,
            ): AppResult<Boolean> {
                assertEquals(this@SpaceOriginalDeliveryFixture.access, access)
                val entered = read("update") { Unit }
                if (entered is AppResult.Failed) return entered
                assertEquals(expected, records[expected.intent.id])
                if (commitUpdate)
                    records[expected.intent.id] =
                        expected.copy(revision = expected.revision + 1, state = state)
                return updateResponse
            }
        }
    val receipts =
        object : CollaborationReceiptRepository {
            override suspend fun read(
                access: CollaborationLocalAccess,
                conversationId: String,
            ): AppResult<List<CollaborationMessageReceipt>> {
                assertEquals(this@SpaceOriginalDeliveryFixture.access, access)
                assertEquals(queue.position.conversationId, conversationId)
                return read("journal") { journal }
            }

            override suspend fun append(
                access: CollaborationLocalAccess,
                conversationId: String,
                receipts: List<CollaborationMessageReceipt>,
            ): AppResult<Boolean> {
                assertEquals(this@SpaceOriginalDeliveryFixture.access, access)
                assertEquals(queue.position.conversationId, conversationId)
                check(receipts.size in 1..32)
                return read("append") {
                    val next = journal.associateBy { it.intent.id }.toMutableMap()
                    for (receipt in receipts) {
                        val previous = next[receipt.intent.id]
                        if (previous != null)
                            assertEquals(previous.copy(eventIds = receipt.eventIds), receipt)
                        next[receipt.intent.id] =
                            receipt.copy(
                                eventIds =
                                    ((previous?.eventIds ?: emptyList()) + receipt.eventIds)
                                        .distinct()
                            )
                    }
                    journal = next.values.toList()
                    true
                }
            }
        }
    val completions =
        object : CollaborationMessageCompletionRepository {
            override suspend fun readCompletion(
                access: CollaborationLocalAccess,
                id: String,
            ): AppResult<CollaborationMessageCompletion?> {
                assertEquals(this@SpaceOriginalDeliveryFixture.access, access)
                return read("completion") { archived[id] }
            }

            override suspend fun archiveCompletion(
                access: CollaborationLocalAccess,
                completion: CollaborationMessageCompletion,
            ): AppResult<Boolean> = error("Keep history; separate retirement")
        }
    val space = SpaceDeliveryStorageFixture(this)
    val history = SpaceOriginalReceiptHistoryFixture(this)
    val useCase =
        DeliverCollaborationSpaceOriginalMessages(
            bindings,
            transport,
            runtimes,
            history.stored,
            history.provider,
            messages,
            receipts,
            completions,
            space.messages,
            space.receipts,
            space.completions,
            directory.directory,
            native.setup.sessions,
            native.server,
            AppBuild(AppPlatform.Android, 5, "test", null),
            native.clock,
            native.time,
        )

    suspend fun deliver() = useCase(queue.runtime, model.conversation)

    fun receipt(event: String = "\$accepted") =
        CollaborationMessageReceipt(
            queue.record.intent,
            queue.device,
            queue.original.transactionId,
            listOf(event),
        )

    fun delivery() =
        CollaborationQueueDelivery(
            listOf(CollaborationQueueReceipt(queue.original.transactionId, "\$accepted")),
            emptySet(),
            CollaborationQueueDeliveryReason.DRAINED,
        )

    fun count(stage: String) = calls.count { it == stage }
}
