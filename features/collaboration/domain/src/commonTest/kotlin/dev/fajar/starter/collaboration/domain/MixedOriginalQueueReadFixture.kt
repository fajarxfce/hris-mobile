package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationMixedOriginalQueue
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.Flow

internal class MixedOriginalQueueReadFixture {
    val base = SpaceOriginalDeliveryFixture()
    val access = base.queue.owner.localDirectoryAccess()
    val original =
        base.queue.original.copy(
            transactionId = "space-transaction",
            logicalId = base.queue.id(301),
        )
    val record =
        CollaborationSpaceMessageRecord(
            CollaborationSpaceMessageIntent(
                requireNotNull(original.logicalId),
                access.messageOwner(),
                base.model.conversation.id,
                base.queue.record.intent.createdAt,
                base.queue.record.intent.text,
            ),
            2,
            CollaborationMessageState.Staged(
                base.queue.device,
                original.transactionId,
                original.origin,
            ),
        )
    val records = mutableMapOf(record.intent.id to record)
    val completed = mutableMapOf<String, CollaborationSpaceMessageCompletion>()

    init {
        base.snapshot =
            base.snapshot.copy(
                original =
                    base.snapshot.original.copy(messages = listOf(base.queue.original, original))
            )
    }

    private val runtimes =
        object : CollaborationSpaceRuntimeRepository by base.runtimes {
            override suspend fun inspectOriginalQueue(
                runtime: CollaborationSpaceRuntimeReady,
                matrixRoomId: String,
            ): AppResult<CollaborationSpaceOriginalQueueSnapshot> {
                assertEquals(base.queue.runtime, runtime)
                assertEquals(base.snapshot.original.device.roomId, matrixRoomId)
                return base.read("inspect") { base.snapshot }
            }
        }

    private val messages =
        object : CollaborationSpaceMessageRepository {
            override fun observe(
                access: CollaborationSpaceLocalAccess,
                conversationId: String?,
            ): Flow<AppResult<List<CollaborationSpaceMessageRecord>>> = error("No observation")

            override fun observeRecord(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): Flow<AppResult<CollaborationSpaceMessageRecord?>> = error("Exact read only")

            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageRecord?> {
                assertEquals(this@MixedOriginalQueueReadFixture.access, access)
                return base.read("space-get") { records[id] }
            }

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                intent: CollaborationSpaceMessageIntent,
            ): AppResult<CollaborationMessageInsertion> = error("No insertion")

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceMessageRecord,
                state: CollaborationMessageState,
            ): AppResult<Boolean> = error("No projection")
        }

    private val completions =
        object : CollaborationSpaceMessageCompletionRepository {
            override suspend fun readCompletion(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageCompletion?> {
                assertEquals(this@MixedOriginalQueueReadFixture.access, access)
                return base.read("space-completion") { completed[id] }
            }

            override suspend fun archiveCompletion(
                access: CollaborationSpaceLocalAccess,
                completion: CollaborationSpaceMessageCompletion,
                repair: CollaborationSpaceInboxRepairPublication,
            ): AppResult<Boolean> = error("No retirement")
        }

    private val useCase =
        ReadCollaborationMixedOriginalQueue(
            base.bindings,
            runtimes,
            base.messages,
            base.completions,
            messages,
            completions,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
        )

    suspend fun read() = useCase(base.queue.runtime, base.model.conversation)

    fun sent() =
        record.copy(
            revision = 3,
            state =
                CollaborationMessageState.Sent(
                    base.queue.device,
                    original.transactionId,
                    "\$space-accepted",
                    original.origin,
                ),
        )

    fun noDelivery() {
        for (name in
            listOf(
                "transport",
                "acquire",
                "resume",
                "settle",
                "append",
                "update",
                "close",
            )) assertEquals(0, base.count(name), name)
    }
}
