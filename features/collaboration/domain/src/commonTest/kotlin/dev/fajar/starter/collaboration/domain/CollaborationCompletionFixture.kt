package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageCompletionRepository
import dev.fajar.starter.common.result.*

internal class CollaborationCompletionFixture(private val base: MessageIntentFixture) :
    CollaborationMessageCompletionRepository {
    val records = mutableMapOf<String, CollaborationMessageCompletion>()
    var reads = 0
    var writes = 0
    var rejectWrite = false
    var readFailure: Failure? = null
    var writeFailure: Failure? = null
    var afterRead: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}

    override suspend fun readCompletion(
        access: CollaborationLocalAccess,
        id: String,
    ): AppResult<CollaborationMessageCompletion?> {
        reads++
        val result = readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(records[id])
        afterRead()
        return result
    }

    override suspend fun archiveCompletion(
        access: CollaborationLocalAccess,
        completion: CollaborationMessageCompletion,
    ): AppResult<Boolean> {
        writes++
        val previous = records[completion.record.intent.id]
        if (previous != null) return AppResult.Success(previous == completion)
        if (rejectWrite || completion.record !in base.records.value) return AppResult.Success(false)
        records[completion.record.intent.id] =
            completion.copy(eventIds = completion.eventIds.toList())
        base.records.value =
            base.records.value.filterNot { it.intent.id == completion.record.intent.id }
        afterWrite()
        return writeFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
    }
}
