package dev.fajar.starter.collaboration.presentation.inbox

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*

internal class ChatInboxContentFixture {
    val messages = MutableStateFlow<Map<String, CollaborationHistoryMessage>>(emptyMap())
    val slots = MutableStateFlow<Map<String, CollaborationDraft>>(emptyMap())
    var historyStarts = 0
    var historyObservers = 0
    var draftObservers = 0
    var historyFailureOnOpen: Failure? = null
    var historyReadGate: CompletableDeferred<Unit>? = null

    val history =
        object : CollaborationHistoryRepository {
            override fun observe(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String?,
                after: String?,
                limit: Int,
                before: String?,
                overlap: Int,
            ) =
                flow<AppResult<CollaborationHistoryPage>> {
                    assertEquals(1, limit)
                    assertNull(after)
                    assertNull(before)
                    assertEquals(0, overlap)
                    historyStarts++
                    historyObservers++
                    try {
                        historyReadGate?.await()
                        historyFailureOnOpen?.let {
                            emit(AppResult.Failed(it))
                            return@flow
                        }
                        emitAll(
                            messages.map {
                                AppResult.Success(
                                    CollaborationHistoryPage(
                                        listOfNotNull(it[conversationId]),
                                        null,
                                        roomId = "!fictional:example.test",
                                    )
                                )
                            }
                        )
                    } finally {
                        historyObservers--
                    }
                }

            override suspend fun getEventFact(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryEventFact?> = error("No event lookup")

            override suspend fun getByEvent(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No event lookup")

            override suspend fun getByLogicalId(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                sender: String,
                logicalId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No message lookup")

            override suspend fun merge(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                changes: List<CollaborationHistoryChange>,
            ): AppResult<Boolean> = error("No history acquisition")
        }
    val drafts =
        object : CollaborationDraftRepository {
            override fun observe(access: CollaborationLocalAccess, conversationId: String) = flow {
                draftObservers++
                try {
                    emitAll(slots.map { AppResult.Success(it[conversationId]) })
                } finally {
                    draftObservers--
                }
            }

            override suspend fun get(
                access: CollaborationLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationDraft?> = error("No draft mutation")

            override suspend fun prepared(
                access: CollaborationLocalAccess,
                afterConversationId: String?,
                limit: Int,
            ): AppResult<CollaborationPreparedDraftPage> = error("No draft delivery")

            override suspend fun replace(
                access: CollaborationLocalAccess,
                expected: CollaborationDraft?,
                replacement: CollaborationDraft,
            ): AppResult<Boolean> = error("No draft mutation")
        }
}
