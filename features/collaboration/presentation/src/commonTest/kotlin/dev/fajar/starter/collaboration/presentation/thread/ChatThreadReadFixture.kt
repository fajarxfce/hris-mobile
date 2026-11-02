package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class ChatThreadReadFixture(private val thread: ChatThreadFixture) {
    var state = CollaborationReadState(null, emptySet(), emptyList(), null)
    var beforeRead: suspend () -> Unit = {}
    var beforeMark: suspend () -> Unit = {}
    var afterMark: suspend () -> Unit = {}
    var readFailure: Failure? = null
    var markFailure: Failure? = null
    var reads = 0
    var activeMarks = 0
    var maxActiveMarks = 0
    var wakeCount = 0
    val marks = mutableListOf<CollaborationReadReceiptIntent>()
    val selections = mutableListOf<List<String>>()
    val repository =
        object : CollaborationReadStateRepository {
            override suspend fun read(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventIds: List<String>,
            ): AppResult<CollaborationReadState> {
                reads++
                beforeRead()
                readFailure?.let {
                    return AppResult.Failed(it)
                }
                return AppResult.Success(
                    state.copy(readEventIds = state.readEventIds.intersect(eventIds.toSet()))
                )
            }

            override suspend fun mark(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                expected: CollaborationReadStateRevision?,
                eventIds: List<String>,
                intent: CollaborationReadReceiptIntent,
            ): AppResult<Boolean> {
                activeMarks++
                maxActiveMarks = maxOf(maxActiveMarks, activeMarks)
                try {
                    beforeMark()
                    currentCoroutineContext().ensureActive()
                    assertEquals(thread.conversationId, conversationId)
                    assertEquals(thread.source.roomId, roomId)
                    if (state.version != expected) return AppResult.Success(false)
                    marks += intent
                    selections += eventIds.toList()
                    state =
                        CollaborationReadState(
                            CollaborationReadStateRevision(
                                chatId(71),
                                (state.version?.revision ?: -1) + 1,
                            ),
                            state.readEventIds + eventIds,
                            if (intent in state.pending || state.acknowledged == intent)
                                state.pending
                            else state.pending + intent,
                            state.acknowledged,
                        )
                    afterMark()
                    return markFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
                } finally {
                    activeMarks--
                }
            }

            override suspend fun acknowledge(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                intent: CollaborationReadReceiptIntent,
            ): AppResult<Boolean> = error("A viewport does not acknowledge provider delivery")
        }
    private var wake: CollaborationRecoveryWake? = null
    private val wakes =
        object : CollaborationRecoveryWakeRepository {
            override suspend fun pending() = AppResult.Success(wake)

            override suspend fun request(): AppResult<CollaborationRecoveryWake> {
                wakeCount++
                return AppResult.Success(
                    CollaborationRecoveryWake(chatId(100 + wakeCount)).also { wake = it }
                )
            }

            override suspend fun acknowledge(wake: CollaborationRecoveryWake): AppResult<Boolean> =
                error("A viewport only requests recovery")
        }
    val reader =
        with(thread.base) {
            ReadCollaborationReadState(
                repository,
                local,
                sessions,
                workspaces,
                server,
                build,
                clock,
            )
        }
    val marker =
        with(thread.base) {
            MarkCollaborationMessagesRead(
                repository,
                thread.history,
                local,
                sessions,
                workspaces,
                server,
                build,
                clock,
            )
        }
    val recovery = RequestCollaborationRecovery(wakes, thread.schedules)

    fun original(
        index: Int = 1,
        sender: String = "@peer:example.test",
    ): CollaborationHistoryMessage {
        val value =
            thread
                .original()
                .copy(
                    primaryEventId = "\$read-$index",
                    eventIds = listOf("\$read-$index"),
                    sender = sender,
                    timestamp = index.toLong(),
                    text = "Fictional visible message $index",
                )
        thread.past.facts[value.primaryEventId] =
            CollaborationHistoryEventFact(
                value.primaryEventId,
                value.sender,
                value.timestamp,
                value.redacted,
            )
        thread.historyRows.value += value
        return value
    }
}
