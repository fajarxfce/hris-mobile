package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecipientObservationRepository
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.*

internal class RecipientObservationFixture : CollaborationRecipientObservationRepository {
    val values = MutableStateFlow<Map<String, CollaborationRecipientObservation>>(emptyMap())
    var readFailure: Failure? = null
    var writeFailure: Failure? = null
    var beforeWrite: suspend () -> Unit = {}
    var beforeObserve: suspend () -> Unit = {}
    var writes = 0

    override fun observe(access: CollaborationLocalAccess, conversationId: String) = flow {
        beforeObserve()
        emitAll(values.map { AppResult.Success(it[conversationId]) })
    }

    override suspend fun get(
        access: CollaborationLocalAccess,
        conversationId: String,
    ): AppResult<CollaborationRecipientObservation?> =
        readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(values.value[conversationId])

    override suspend fun replace(
        access: CollaborationLocalAccess,
        expected: CollaborationRecipientObservation?,
        replacement: CollaborationRecipientObservation,
    ): AppResult<Boolean> {
        beforeWrite()
        writeFailure?.let {
            return AppResult.Failed(it)
        }
        val current = values.value[replacement.conversationId]
        if (current == replacement) return AppResult.Success(true)
        if (current != expected) return AppResult.Success(false)
        values.value = values.value + (replacement.conversationId to replacement)
        writes++
        return AppResult.Success(true)
    }
}
