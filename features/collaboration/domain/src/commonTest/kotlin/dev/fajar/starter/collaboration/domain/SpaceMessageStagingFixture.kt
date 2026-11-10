package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.StageCollaborationSpaceText
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.*

/** Owning-usecase fixture. Raw enqueue is synthetic; actual SDK acceptance is separate. */
internal class SpaceMessageStagingFixture {
    val base = SpaceOriginalDeliveryFixture()
    val access = base.queue.owner.localDirectoryAccess()
    val intent =
        CollaborationSpaceMessageIntent(
            base.queue.id(301),
            access.messageOwner(),
            base.model.conversation.id,
            base.queue.record.intent.createdAt,
            "New Space text",
        )
    val initial = CollaborationSpaceMessageRecord(intent, 0, CollaborationMessageState.Queued)
    val records = linkedMapOf(intent.id to initial)
    var journal = emptyList<CollaborationSpaceMessageReceipt>()
    val archived = mutableMapOf<String, CollaborationSpaceMessageCompletion>()
    var preparedOrigin = base.queue.origin
    var commitEnqueue = true
    var enqueueResponse: AppResult<Unit> = AppResult.Success(Unit)
    var closeResponse: AppResult<Unit> = AppResult.Success(Unit)
    var commitStaging = true
    var commitStaged = true
    var stagingResponse: AppResult<Boolean> = AppResult.Success(true)
    var stagedResponse: AppResult<Boolean> = AppResult.Success(true)
    var activeWriter = false
    val transactionId = "new-space-original-transaction"
    val messages =
        object : CollaborationSpaceMessageRepository {
            override fun observe(
                access: CollaborationSpaceLocalAccess,
                conversationId: String?,
            ): Flow<AppResult<List<CollaborationSpaceMessageRecord>>> = flow {
                assertEquals(this@SpaceMessageStagingFixture.access, access)
                assertEquals(base.model.conversation.id, conversationId)
                emit(base.read("space-observe") { records.values.toList() })
            }

            override fun observeRecord(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): Flow<AppResult<CollaborationSpaceMessageRecord?>> = error("Exact command reads only")

            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageRecord?> {
                assertEquals(this@SpaceMessageStagingFixture.access, access)
                return base.read("space-get") { records[id] ?: archived[id]?.record }
            }

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                intent: CollaborationSpaceMessageIntent,
            ): AppResult<CollaborationMessageInsertion> = error("Intent already retained")

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceMessageRecord,
                state: CollaborationMessageState,
            ): AppResult<Boolean> {
                assertEquals(this@SpaceMessageStagingFixture.access, access)
                assertTrue(activeWriter)
                val step =
                    if (state is CollaborationMessageState.Staging) "store-staging"
                    else "store-staged"
                val entered = base.read(step) { Unit }
                if (entered is AppResult.Failed) return entered
                assertEquals(expected, records[expected.intent.id])
                val commit =
                    if (state is CollaborationMessageState.Staging) commitStaging else commitStaged
                if (commit)
                    records[expected.intent.id] =
                        expected.copy(revision = expected.revision + 1, state = state)
                base.hooks[step + "-committed"]?.invoke()
                return if (state is CollaborationMessageState.Staging) stagingResponse
                else stagedResponse
            }
        }
    val receipts =
        object : CollaborationSpaceReceiptRepository {
            override suspend fun read(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<List<CollaborationSpaceMessageReceipt>> {
                assertEquals(this@SpaceMessageStagingFixture.access, access)
                assertEquals(base.model.conversation.id, conversationId)
                return base.read("space-journal") { journal }
            }

            override suspend fun append(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                receipts: List<CollaborationSpaceMessageReceipt>,
            ): AppResult<Boolean> = error("Never invent acknowledgements")
        }
    val completions =
        object : CollaborationSpaceMessageCompletionRepository {
            override suspend fun readCompletion(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageCompletion?> {
                assertEquals(this@SpaceMessageStagingFixture.access, access)
                return base.read("space-completion") { archived[id] }
            }

            override suspend fun archiveCompletion(
                access: CollaborationSpaceLocalAccess,
                completion: CollaborationSpaceMessageCompletion,
            ): AppResult<Boolean> = error("Never retire")
        }
    val queues =
        object : CollaborationSpaceMessageQueueRepository {
            override suspend fun acquireSpaceMessageQueue(
                runtime: CollaborationSpaceRuntimeReady,
                matrixRoomId: String,
            ): AppResult<CollaborationSpaceMessageQueueLease> {
                assertEquals(base.queue.runtime, runtime)
                assertEquals(base.snapshot.original.device.roomId, matrixRoomId)
                val entered = base.read("stage-acquire") { Unit }
                if (entered is AppResult.Failed) return entered
                assertFalse(activeWriter)
                activeWriter = true
                return AppResult.Success(Lease())
            }
        }

    private inner class Lease : CollaborationSpaceMessageQueueLease {
        override val runtime = base.queue.runtime
        override val binding = base.snapshot.original.binding
        override val device = base.snapshot.original.device

        override suspend fun snapshot(): AppResult<CollaborationSpaceOriginalQueueSnapshot> {
            assertTrue(activeWriter)
            return base.read("stage-snapshot") { base.snapshot }
        }

        override suspend fun prepareText(
            logicalId: String,
            text: String,
            replyToEventId: String?,
            forwarded: Boolean,
            editTarget: CollaborationEditTarget?,
        ): AppResult<CollaborationMessageOrigin> {
            assertEquals(intent.id, logicalId)
            assertEquals(intent.text, text)
            assertEquals(intent.replyToEventId, replyToEventId)
            assertEquals(intent.forwarded, forwarded)
            assertEquals(intent.editTarget, editTarget)
            return base.read("stage-prepare") { preparedOrigin }
        }

        override suspend fun enqueue(
            expected: CollaborationSpaceOriginalQueueSnapshot,
            logicalId: String,
            text: String,
            origin: CollaborationMessageOrigin,
            replyToEventId: String?,
            forwarded: Boolean,
            editTarget: CollaborationEditTarget?,
        ): AppResult<Unit> {
            assertTrue(activeWriter)
            assertEquals(base.snapshot, expected)
            assertEquals(intent.id, logicalId)
            assertEquals(intent.text, text)
            assertEquals(preparedOrigin, origin)
            val retained =
                assertIs<CollaborationMessageState.Staging>(records.getValue(logicalId).state)
            assertEquals(device, retained.device)
            assertEquals(origin, retained.origin)
            assertEquals(
                expected.original.messages.map { it.transactionId }.toSet(),
                retained.before,
            )
            val entered = base.read("stage-enqueue") { Unit }
            if (entered is AppResult.Failed) return entered
            if (commitEnqueue) {
                assertTrue(base.snapshot.original.messages.none { it.logicalId == logicalId })
                val original =
                    base.queue.original.copy(
                        transactionId = transactionId,
                        logicalId = logicalId,
                        origin = origin,
                    )
                base.snapshot =
                    base.snapshot.copy(
                        original =
                            base.snapshot.original.copy(
                                messages = base.snapshot.original.messages + original
                            )
                    )
            }
            base.hooks["stage-enqueued"]?.invoke()
            return enqueueResponse
        }

        override suspend fun close(): AppResult<Unit> {
            val read = base.read("stage-close") { Unit }
            activeWriter = false
            if (read is AppResult.Failed) return read
            return closeResponse
        }
    }

    val useCase =
        StageCollaborationSpaceText(
            messages,
            base.messages,
            receipts,
            base.receipts,
            completions,
            base.completions,
            base.bindings,
            base.transport,
            queues,
            base.runtimes,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            AppBuild(AppPlatform.Android, 5, "test", null),
            base.native.clock,
            base.native.time,
        )

    suspend fun stage() = useCase(base.queue.runtime, base.model.conversation, intent.id)

    fun current() = records.getValue(intent.id)

    fun noDelivery() {
        for (step in listOf("resume", "settle", "append", "update", "history")) assertEquals(
            0,
            base.count(step),
            step,
        )
    }
}
