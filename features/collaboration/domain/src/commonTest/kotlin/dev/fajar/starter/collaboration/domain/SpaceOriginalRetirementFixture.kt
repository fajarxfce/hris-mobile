package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.RetireCollaborationSpaceOriginals
import dev.fajar.starter.common.result.*
import kotlin.test.*

internal class SpaceOriginalRetirementFixture {
    val repair = SpaceInboxRepairUseCaseFixture()
    val base = repair.base
    val access = repair.access
    val conversation = repair.conversation.id
    val journal = mutableListOf<CollaborationSpaceMessageReceipt>()
    val records = mutableMapOf<String, CollaborationSpaceMessageRecord>()
    val archives = mutableMapOf<String, CollaborationSpaceMessageCompletion>()
    val authored = mutableMapOf<String, CollaborationSpaceMessageRecord>()
    val originals = mutableMapOf<String, CollaborationHistoryMessage>()
    val publications =
        mutableListOf<
            Pair<CollaborationSpaceMessageCompletion, CollaborationSpaceInboxRepairPublication>
        >()
    val removals = mutableListOf<List<CollaborationSpaceMessageReceipt>>()
    val operations = mutableListOf<String>()
    var afterIo: suspend (String) -> Unit = {}
    var readFailure: String? = null
    var archiveConflicts = 0
    var onArchive:
        suspend (
            CollaborationSpaceMessageCompletion, CollaborationSpaceInboxRepairPublication,
        ) -> AppResult<Boolean> =
        { completion, publication ->
            if (archiveConflicts > 0) {
                archiveConflicts--
                AppResult.Success(false)
            } else if (repair.stored != publication.expected) AppResult.Success(false)
            else {
                archives[completion.record.intent.id] = completion
                records.remove(completion.record.intent.id)
                repair.stored = publication.replacement
                AppResult.Success(true)
            }
        }
    var onRemove: suspend (List<CollaborationSpaceMessageReceipt>) -> AppResult<Boolean> =
        { expected ->
            if (!journal.containsAll(expected)) AppResult.Success(false)
            else {
                journal.removeAll(expected.toSet())
                AppResult.Success(true)
            }
        }
    val first = addOriginal(1)

    init {
        repair.onCheckpoint = {
            operations += "progress"
            afterIo("progress")
            if (readFailure == "progress") AppResult.Failed(base.storageFailure)
            else AppResult.Success(repair.stored)
        }
    }

    val messages =
        object : CollaborationSpaceMessageRepository by repair.messages {
            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageRecord?> {
                assertEquals(this@SpaceOriginalRetirementFixture.access, access)
                val result =
                    if (readFailure == "message") AppResult.Failed(base.storageFailure)
                    else AppResult.Success(records[id])
                operations += "message"
                afterIo("message")
                return result
            }
        }
    val completions =
        object : CollaborationSpaceMessageCompletionRepository {
            override suspend fun readCompletion(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageCompletion?> {
                assertEquals(this@SpaceOriginalRetirementFixture.access, access)
                val result =
                    if (readFailure == "completion") AppResult.Failed(base.storageFailure)
                    else AppResult.Success(archives[id])
                operations += "completion"
                afterIo("completion")
                return result
            }

            override suspend fun archiveCompletion(
                access: CollaborationSpaceLocalAccess,
                completion: CollaborationSpaceMessageCompletion,
                repair: CollaborationSpaceInboxRepairPublication,
            ): AppResult<Boolean> {
                assertEquals(this@SpaceOriginalRetirementFixture.access, access)
                assertTrue(validCollaborationSpaceInboxRepairPublication(repair))
                publications += completion to repair
                val result =
                    if (readFailure == "archive") AppResult.Failed(base.storageFailure)
                    else onArchive(completion, repair)
                operations += "archive"
                afterIo("archive")
                return result
            }
        }
    val receipts =
        object : CollaborationSpaceReceiptRepository {
            override suspend fun read(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<List<CollaborationSpaceMessageReceipt>> {
                assertEquals(this@SpaceOriginalRetirementFixture.access, access)
                assertEquals(conversation, conversationId)
                val result =
                    if (readFailure == "journal") AppResult.Failed(base.storageFailure)
                    else AppResult.Success(journal.toList())
                operations += "journal"
                afterIo("journal")
                return result
            }

            override suspend fun append(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                receipts: List<CollaborationSpaceMessageReceipt>,
            ): AppResult<Boolean> = error("Retirement never appends receipts")
        }
    val retirement =
        object : CollaborationSpaceReceiptRetirementRepository {
            override suspend fun removeReceipts(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                expected: List<CollaborationSpaceMessageReceipt>,
            ): AppResult<Boolean> {
                assertEquals(this@SpaceOriginalRetirementFixture.access, access)
                assertEquals(conversation, conversationId)
                removals += expected.toList()
                val result =
                    if (readFailure == "remove") AppResult.Failed(base.storageFailure)
                    else onRemove(expected)
                operations += "remove"
                afterIo("remove")
                return result
            }
        }
    val history =
        object : CollaborationSpaceHistoryRepository {
            override suspend fun getByLogicalId(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                sender: String,
                logicalId: String,
            ): AppResult<CollaborationHistoryMessage?> {
                assertEquals(this@SpaceOriginalRetirementFixture.access, access)
                assertEquals(conversation, conversationId)
                val sent = authored.getValue(logicalId).state as CollaborationMessageState.Sent
                assertEquals(sent.device.roomId, roomId)
                assertEquals(sent.origin?.sender, sender)
                val result =
                    if (readFailure == "history") AppResult.Failed(base.storageFailure)
                    else AppResult.Success(originals[logicalId])
                operations += "history"
                afterIo("history")
                return result
            }

            override suspend fun getEventFact(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryEventFact?> = error("No event scan")

            override fun observe(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String?,
                after: String?,
                limit: Int,
                before: String?,
                overlap: Int,
            ): kotlinx.coroutines.flow.Flow<AppResult<CollaborationHistoryPage>> =
                error("No history stream")

            override suspend fun getByEvent(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No event scan")

            override suspend fun merge(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                changes: List<CollaborationHistoryChange>,
            ): AppResult<Boolean> = error("No history mutation")
        }

    fun retire() =
        RetireCollaborationSpaceOriginals(
            messages,
            completions,
            receipts,
            retirement,
            history,
            repair.progress,
            repair.directory,
            repair.sessions,
            base.native.server,
        )

    fun addOriginal(number: Int): CollaborationSpaceMessageRecord {
        val sample = repair.sample.sent()
        val sent =
            (sample.state as CollaborationMessageState.Sent).copy(
                transactionId = "transaction-$number",
                eventId = "\$event-$number",
            )
        val record =
            sample.copy(
                intent = repair.record.intent.copy(id = repair.id(1000 + number)),
                state = sent,
            )
        assertTrue(validCollaborationSpaceMessageRecord(access, record))
        val receipt =
            CollaborationSpaceMessageReceipt(
                record.intent,
                sent.device,
                sent.transactionId,
                requireNotNull(sent.origin),
                listOf(sent.eventId),
            )
        records[record.intent.id] = record
        authored[record.intent.id] = record
        journal += receipt
        originals[record.intent.id] =
            assertNotNull(
                mergeCollaborationHistoryText(
                    null,
                    CollaborationHistoryChange.Text(
                        sent.eventId,
                        receipt.origin.sender,
                        record.intent.id,
                        100,
                        record.intent.text,
                        receipt.origin.contentHash,
                        replyToEventId = record.intent.replyToEventId,
                        forwarded = record.intent.forwarded,
                    ),
                )
            )
        return record
    }
}
