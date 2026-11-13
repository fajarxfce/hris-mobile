package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReconcileCollaborationSpaceMessages
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals

internal class SpaceReceiptProjectionFixture {
    val source = SpaceMessageUseCaseFixture()
    val access = source.access
    val conversation = source.room.conversation.id
    val device = CollaborationMessageDevice(id(1), id(2), id(3), "!space:matrix.example.test")
    val origin = CollaborationMessageOrigin("@employee:matrix.example.test", "a".repeat(64))
    val original = receipt(1)
    val staged = staged(original)
    val sent = sent(staged)
    val completion = CollaborationSpaceMessageCompletion(sent, original.eventIds)
    var journal: AppResult<List<CollaborationSpaceMessageReceipt>> =
        AppResult.Success(listOf(original))
    val records = mutableMapOf(staged.intent.id to staged)
    val archives = mutableMapOf<String, CollaborationSpaceMessageCompletion>()
    val operations = mutableListOf<String>()
    val writes = mutableListOf<CollaborationSpaceMessageRecord>()
    var afterIo: suspend (String) -> Unit = {}
    var beforeAdmission: suspend () -> Unit = {}
    var readFailure: Failure? = null
    var archiveFailure: Failure? = null
    var writeResult: AppResult<Boolean> = AppResult.Success(true)
    var retainWrite = true
    var savedRecord: (CollaborationSpaceMessageRecord) -> CollaborationSpaceMessageRecord? = { it }

    val messages =
        object : CollaborationSpaceMessageRepository by source.messages {
            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageRecord?> {
                val result =
                    readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(records[id])
                operations += "read"
                afterIo("read")
                return result
            }

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceMessageRecord,
                state: CollaborationMessageState,
            ): AppResult<Boolean> {
                assertEquals(records[expected.intent.id], expected)
                writes += expected
                if (retainWrite) {
                    val saved =
                        savedRecord(expected.copy(revision = expected.revision + 1, state = state))
                    if (saved == null) records.remove(expected.intent.id)
                    else records[expected.intent.id] = saved
                }
                operations += "write"
                afterIo("write")
                return writeResult
            }
        }
    val receipts =
        object : CollaborationSpaceReceiptRepository {
            override suspend fun read(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<List<CollaborationSpaceMessageReceipt>> {
                assertEquals(conversation, conversationId)
                operations += "journal"
                afterIo("journal")
                return journal
            }

            override suspend fun append(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                receipts: List<CollaborationSpaceMessageReceipt>,
            ): AppResult<Boolean> = error("Projection never appends or sends")
        }
    val completions =
        object : CollaborationSpaceMessageCompletionRepository {
            override suspend fun readCompletion(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageCompletion?> {
                val result =
                    archiveFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(archives[id])
                operations += "archive"
                afterIo("archive")
                return result
            }

            override suspend fun archiveCompletion(
                access: CollaborationSpaceLocalAccess,
                completion: CollaborationSpaceMessageCompletion,
                repair: CollaborationSpaceInboxRepairPublication,
            ): AppResult<Boolean> = error("History retirement has a separate owner")
        }
    val directory =
        object : CollaborationSpaceDirectoryRepository by source.base.directory {
            override suspend fun admission(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceDirectoryAdmission> {
                beforeAdmission()
                return source.base.gate.value
            }
        }

    fun useCase() =
        ReconcileCollaborationSpaceMessages(
            messages,
            receipts,
            completions,
            directory,
            source.sessions,
            source.native.server,
        )

    fun receipt(index: Int) =
        CollaborationSpaceMessageReceipt(
            source.record().intent.copy(id = id(100 + index), text = "Original $index"),
            device,
            "transaction-$index",
            origin,
            listOf("\$event-$index"),
        )

    fun staged(receipt: CollaborationSpaceMessageReceipt) =
        CollaborationSpaceMessageRecord(
            receipt.intent,
            2,
            CollaborationMessageState.Staged(receipt.device, receipt.transactionId, receipt.origin),
        )

    fun sent(record: CollaborationSpaceMessageRecord): CollaborationSpaceMessageRecord {
        val state = record.state as CollaborationMessageState.Staged
        return record.copy(
            revision = record.revision + 1,
            state =
                CollaborationMessageState.Sent(
                    state.device,
                    state.transactionId,
                    "\$event-1",
                    state.origin,
                ),
        )
    }

    companion object {
        fun id(value: Int) = "a6000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
    }
}
