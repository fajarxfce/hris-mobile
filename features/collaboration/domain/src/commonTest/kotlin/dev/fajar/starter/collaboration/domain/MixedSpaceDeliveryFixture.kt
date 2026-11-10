package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.DeliverCollaborationSpaceOriginalMessages
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

/** One physical paused queue, two independently owned durable stores. No native FFI claim. */
internal class MixedSpaceDeliveryFixture {
    val source = MixedOriginalQueueReadFixture()
    val base = source.base
    val space = base.space
    val original = source.original
    val record = source.record
    var removeSettled = true
    val lease =
        object : CollaborationSpaceOriginalQueueLease by base.lease {
            override val device
                get() = base.snapshot.original.device

            override suspend fun settleConfirmed(
                original: CollaborationQueuedOriginal,
                eventId: String,
            ): AppResult<CollaborationQueueSettlement> {
                assertEquals(
                    if (original.logicalId == record.intent.id)
                        this@MixedSpaceDeliveryFixture.original
                    else base.queue.original,
                    original,
                )
                assertEquals(
                    if (original.logicalId == record.intent.id) "\$space-accepted"
                    else "\$accepted",
                    eventId,
                )
                return base.read("settle") {
                    if (removeSettled)
                        base.snapshot =
                            base.snapshot.copy(
                                original =
                                    base.snapshot.original.copy(
                                        messages =
                                            base.snapshot.original.messages.filterNot {
                                                it == original
                                            }
                                    )
                            )
                    CollaborationQueueSettlement.Settled
                }
            }
        }
    val runtimes =
        object : CollaborationSpaceRuntimeRepository by base.runtimes {
            override suspend fun acquireOriginalQueue(
                runtime: CollaborationSpaceRuntimeReady,
                matrixRoomId: String,
            ): AppResult<CollaborationSpaceOriginalQueueLease> {
                assertEquals(base.queue.runtime, runtime)
                assertEquals(base.snapshot.original.device.roomId, matrixRoomId)
                return base.read("acquire") { lease }
            }
        }
    val useCase =
        DeliverCollaborationSpaceOriginalMessages(
            base.bindings,
            base.transport,
            runtimes,
            base.history.stored,
            base.history.provider,
            base.messages,
            base.receipts,
            base.completions,
            space.messages,
            space.receipts,
            space.completions,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            AppBuild(AppPlatform.Android, 5, "test", null),
            base.native.clock,
            base.native.time,
        )

    init {
        space.records[record.intent.id] = record
        base.onResume = { observer ->
            val outcome = progress()
            base.snapshot =
                base.snapshot.copy(original = base.snapshot.original.copy(messages = emptyList()))
            when (val saved = observer(outcome)) {
                is AppResult.Failed -> saved
                is AppResult.Success -> AppResult.Success(outcome)
            }
        }
    }

    fun progress() =
        base
            .delivery()
            .copy(
                receipts =
                    base.delivery().receipts +
                        CollaborationQueueReceipt(original.transactionId, "\$space-accepted")
            )

    fun receipt() =
        CollaborationSpaceMessageReceipt(
            record.intent,
            base.snapshot.original.device,
            original.transactionId,
            original.origin,
            listOf("\$space-accepted"),
        )

    fun spaceEvent() =
        base.history.event().copy(eventId = "\$space-accepted", logicalId = record.intent.id)

    suspend fun deliver() = useCase(base.queue.runtime, base.model.conversation)

    fun sent() = space.records.getValue(record.intent.id)

    fun noSend() {
        assertEquals(0, base.count("resume"))
        assertEquals(0, base.count("settle"))
    }

    fun newRoom() {
        val room =
            base.model.binding.room.copy(
                roomId = "!space-new:${base.model.binding.room.serverName}",
                origin = CollaborationSpaceRoomOrigin.SPACE,
                original = null,
            )
        base.retained = base.model.binding.copy(room = room, original = null)
        base.observed = base.observed.copy(room = room)
        val device = base.queue.device.copy(roomId = room.roomId)
        base.snapshot =
            base.snapshot.copy(
                original = base.snapshot.original.copy(device = device, messages = listOf(original))
            )
        space.records[record.intent.id] =
            record.copy(
                state = (record.state as CollaborationMessageState.Staged).copy(device = device)
            )
        base.onResume = { observer ->
            val outcome =
                progress()
                    .copy(
                        receipts =
                            listOf(
                                CollaborationQueueReceipt(
                                    original.transactionId,
                                    "\$space-accepted",
                                )
                            )
                    )
            base.snapshot =
                base.snapshot.copy(original = base.snapshot.original.copy(messages = emptyList()))
            when (val saved = observer(outcome)) {
                is AppResult.Failed -> saved
                is AppResult.Success -> AppResult.Success(outcome)
            }
        }
    }
}
