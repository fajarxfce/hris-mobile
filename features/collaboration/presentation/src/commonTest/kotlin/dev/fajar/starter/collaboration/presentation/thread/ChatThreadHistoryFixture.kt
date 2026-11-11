package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.flow

internal class ChatThreadHistoryFixture(private val thread: ChatThreadFixture) {
    val pages = ArrayDeque<List<CollaborationProviderHistoryEvent>>()
    var progress: CollaborationHistoryProgress? = null
    var references = emptyList<CollaborationHistoryReference>()
    val originals = mutableMapOf<String, CollaborationHistoryChange.Text>()
    val facts = mutableMapOf<String, CollaborationHistoryEventFact>()
    val requestedPositions = mutableListOf<String?>()
    val anchors = mutableListOf<String>()
    var beforeProvider: suspend () -> Unit = {}
    var beforeOriginal: suspend () -> Unit = {}
    var beforeReferences: suspend () -> Unit = {}
    var providerFailure: Failure? = null
    var originalFailure: Failure? = null
    var stageFailure: Failure? = null
    var stages = 0

    val traversal =
        object : CollaborationHistoryTraversalRepository {
            override suspend fun source(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                eventId: String,
            ): AppResult<CollaborationHistorySource> =
                error("Exact source is not part of this fixture")

            override suspend fun advanceIncomingReferences(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                source: CollaborationHistoryReferences,
                retryRequired: Boolean,
            ): AppResult<Boolean> = error("No implicit incoming seek advance")

            override suspend fun progress(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
            ) = AppResult.Success(progress)

            override fun observeReferences(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                after: String?,
                limit: Int,
            ): kotlinx.coroutines.flow.Flow<AppResult<CollaborationHistoryReferences>> = flow {
                beforeReferences()
                val pending =
                    references.filter {
                        !it.resolved &&
                            (after == null || it.sequence > after.removePrefix("after-").toLong())
                    }
                emit(
                    AppResult.Success(
                        CollaborationHistoryReferences(
                            progress,
                            pending.take(limit),
                            if (pending.size > limit) "after-${pending[limit - 1].sequence}"
                            else null,
                        )
                    )
                )
            }

            override suspend fun stage(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress?,
                page: CollaborationProviderHistoryPage,
                origin: CollaborationHistoryPageOrigin,
            ): AppResult<Boolean> {
                assertEquals(progress, expected)
                stages++
                stageFailure?.let {
                    return AppResult.Failed(it)
                }
                page.events.forEach { event ->
                    if (references.none { it.event.eventId == event.eventId })
                        references += CollaborationHistoryReference(references.size.toLong(), event)
                }
                progress =
                    CollaborationHistoryProgress(
                        chatId(60),
                        (progress?.revision ?: -1) + 1,
                        page.next,
                        references.size.toLong(),
                    )
                return AppResult.Success(true)
            }

            override suspend fun resolve(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress,
                references: List<CollaborationHistoryReference>,
            ): AppResult<Boolean> {
                assertEquals(progress, expected)
                this@ChatThreadHistoryFixture.references =
                    this@ChatThreadHistoryFixture.references.map {
                        if (it in references) it.copy(resolved = true) else it
                    }
                progress = expected.copy(revision = expected.revision + 1)
                return AppResult.Success(true)
            }
        }

    suspend fun provider(
        owner: CollaborationOwner,
        deviceId: String,
        roomId: String,
        position: String?,
    ): AppResult<CollaborationProviderHistoryPage> {
        requestedPositions += position
        beforeProvider()
        providerFailure?.let {
            return AppResult.Failed(it)
        }
        val events = pages.removeFirstOrNull().orEmpty()
        return AppResult.Success(
            CollaborationProviderHistoryPage(
                owner,
                deviceId,
                roomId,
                position,
                position ?: "start",
                if (pages.isEmpty()) null else "provider-${requestedPositions.size}",
                events,
            )
        )
    }

    suspend fun original(
        owner: CollaborationOwner,
        deviceId: String,
        roomId: String,
        anchor: String,
    ): AppResult<CollaborationOriginalWindow> {
        anchors += anchor
        beforeOriginal()
        originalFailure?.let {
            return AppResult.Failed(it)
        }
        val values = references.mapNotNull { originals[it.event.eventId] }
        return AppResult.Success(
            CollaborationOriginalWindow(
                owner,
                deviceId,
                roomId,
                anchor,
                values,
                if (anchor in originals) emptyList() else listOf(anchor),
            )
        )
    }

    fun merge(changes: List<CollaborationHistoryChange>): AppResult<Boolean> {
        changes.forEach { change ->
            val text = assertIs<CollaborationHistoryChange.Text>(change)
            facts[text.eventId] =
                CollaborationHistoryEventFact(text.eventId, text.sender, text.timestamp, false)
            val row =
                CollaborationHistoryMessage(
                    text.sender,
                    text.logicalId,
                    text.eventId,
                    text.timestamp,
                    text.contentHash,
                    text.text,
                    listOf(text.eventId),
                    false,
                )
            thread.historyRows.value =
                (thread.historyRows.value.filterNot { it.primaryEventId == text.eventId } + row)
                    .sortedByDescending { it.timestamp }
        }
        return AppResult.Success(true)
    }

    fun event(index: Int, readable: Boolean = true): CollaborationProviderHistoryEvent {
        val event =
            CollaborationProviderHistoryEvent(
                "\$older-$index",
                "@peer:example.test",
                1000L + index,
                "m.room.encrypted",
            )
        if (readable)
            originals[event.eventId] =
                CollaborationHistoryChange.Text(
                    event.eventId,
                    event.sender,
                    null,
                    event.timestamp,
                    "Older message $index",
                    "b".repeat(64),
                )
        return event
    }
}
