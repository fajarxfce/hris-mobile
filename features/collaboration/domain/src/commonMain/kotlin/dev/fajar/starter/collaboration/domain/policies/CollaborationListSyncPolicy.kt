@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

const val COLLABORATION_LIST_SYNC_CAPACITY = 10_000
const val COLLABORATION_SUMMARY_REVISION_LIMIT = 9_007_199_254_740_991L

data class CollaborationListSyncWrite(
    val checkpoint: CollaborationListSyncCheckpoint,
    val facts: List<CollaborationSummaryFact>,
    val caughtUp: Boolean,
    val retryAfterSeconds: Int,
)

fun planCollaborationSummaryBootstrap(
    expected: CollaborationListSyncCheckpoint,
    page: CollaborationSummaryBootstrap,
    window: CollaborationListSyncWindow,
    at: Instant,
    limit: Int = 50,
): AppResult<CollaborationListSyncWrite> = summaryPlan {
    val state = currentSummaryState(expected, window, at, limit)
    require(page.serverTime.toEpochMilliseconds() >= 0)
    require(!expected.published && state.phase == CollaborationListSyncPhase.BOOTSTRAP)
    require(page.items.size <= limit && (page.nextPage == null) != (page.changesCursor == null))
    require(page.items.zipWithNext().all { (a, b) -> a.conversationId < b.conversationId })
    state.afterConversationId?.let { after ->
        require(page.items.all { it.conversationId > after })
    }
    require(state.bootstrapPages < 1000)
    val next =
        if (page.nextPage != null) {
            require(page.items.size == limit && state.bootstrapPages < 999)
            summaryCursor(page.nextPage)
            state.copy(
                cursor = page.nextPage,
                afterConversationId = page.items.last().conversationId,
                bootstrapPages = state.bootstrapPages + 1,
            )
        } else {
            val cursor = requireNotNull(page.changesCursor)
            summaryCursor(cursor)
            state.copy(
                phase = CollaborationListSyncPhase.CATCHUP,
                cursor = cursor,
                afterConversationId = null,
                bootstrapPages = 0,
            )
        }
    summaryWrite(expected, window.facts, page.items, next, at, false, false, 0)
}

fun planCollaborationSummaryChanges(
    expected: CollaborationListSyncCheckpoint,
    page: CollaborationSummaryChanges,
    window: CollaborationListSyncWindow,
    at: Instant,
    limit: Int = 50,
): AppResult<CollaborationListSyncWrite> = summaryPlan {
    val state = currentSummaryState(expected, window, at, limit)
    require(page.serverTime.toEpochMilliseconds() >= 0)
    require(state.phase != CollaborationListSyncPhase.BOOTSTRAP)
    require(page.items.size <= limit && (!page.hasMore || page.items.size == limit))
    require(page.items.all { it.position > state.lastPosition })
    require(page.items.zipWithNext().all { (a, b) -> a.position < b.position })
    summaryCursor(page.nextCursor)
    val complete = !page.hasMore && !page.pending
    val publish = expected.published || complete
    val next =
        state.copy(
            cursor = page.nextCursor,
            lastPosition = page.items.lastOrNull()?.position ?: state.lastPosition,
            phase =
                if (publish) CollaborationListSyncPhase.CHANGES
                else CollaborationListSyncPhase.CATCHUP,
        )
    summaryWrite(
        expected,
        window.facts,
        page.items.map { it.fact },
        next,
        at,
        publish,
        complete,
        if (page.pending && !page.hasMore) 5 else 0,
    )
}

fun collaborationSummaryCursorNeedsReset(failure: Failure) =
    failure.code in
        setOf(
            "invalid_collaboration_sync_cursor",
            "collaboration_summary_scope_changed",
            "collaboration_summary_cursor_out_of_range",
            "collaboration_summary_cursor_expired",
            "collaboration_summary_bootstrap_limit",
        )

private fun currentSummaryState(
    expected: CollaborationListSyncCheckpoint,
    window: CollaborationListSyncWindow,
    at: Instant,
    limit: Int,
): CollaborationListSyncState {
    require(window.checkpoint == expected && expected.local.format == 2 && limit in 1..50)
    require(expected.local.revision in 0 until COLLABORATION_SUMMARY_REVISION_LIMIT)
    require(
        expected.local.count in 0..COLLABORATION_LIST_SYNC_CAPACITY && at.toEpochMilliseconds() >= 0
    )
    val state = requireNotNull(expected.sync)
    summaryId(state.nativeSessionId)
    require(
        state.lastPosition >= 0 &&
            expected.published == (state.phase == CollaborationListSyncPhase.CHANGES)
    )
    require(window.facts.size <= expected.local.count)
    if (state.phase == CollaborationListSyncPhase.BOOTSTRAP) {
        require(state.lastPosition == 0L && state.bootstrapPages in 0..999)
        require((state.bootstrapPages == 0) == (state.cursor == null))
        require((state.bootstrapPages == 0) == (state.afterConversationId == null))
        state.afterConversationId?.let(::summaryId)
    } else
        require(
            state.cursor != null && state.afterConversationId == null && state.bootstrapPages == 0
        )
    state.cursor?.let(::summaryCursor)
    return state
}

private fun summaryWrite(
    expected: CollaborationListSyncCheckpoint,
    retained: List<CollaborationListStoredFact>,
    incoming: List<CollaborationSummaryFact>,
    nextState: CollaborationListSyncState,
    at: Instant,
    publish: Boolean,
    caughtUp: Boolean,
    retryAfterSeconds: Int,
): CollaborationListSyncWrite {
    require(incoming.size <= 50 && retained.size <= 50)
    require(retained.map { it.conversationId }.distinct().size == retained.size)
    val folded = linkedMapOf<String, CollaborationSummaryFact>()
    for (fact in incoming) {
        summaryId(fact.conversationId)
        require(fact.revision in 0..COLLABORATION_SUMMARY_REVISION_LIMIT)
        require(fact.summary == null || fact.summary.conversation.id == fact.conversationId)
        val previous = folded[fact.conversationId]
        if (previous?.summary != null && fact.summary != null)
            require(previous.summary.conversation == fact.summary.conversation)
        if (previous?.revision == fact.revision) require(previous == fact)
        if (previous == null || fact.revision > previous.revision)
            folded[fact.conversationId] = fact
    }
    require(retained.all { it.conversationId in folded })
    val oldById = retained.associateBy { it.conversationId }
    var added = 0
    val changed = mutableListOf<CollaborationSummaryFact>()
    for (fact in folded.values) {
        val old = oldById[fact.conversationId]
        if (old == null) {
            added++
            changed.add(fact)
            continue
        }
        require(old.revision != null || old.summary != null)
        old.revision?.let { require(it in 0..COLLABORATION_SUMMARY_REVISION_LIMIT) }
        require(old.summary == null || old.summary.conversation.id == old.conversationId)
        if (old.summary != null && fact.summary != null)
            require(old.summary.conversation == fact.summary.conversation)
        val oldRevision = old.revision
        if (oldRevision == null || oldRevision < fact.revision) changed.add(fact)
        else if (oldRevision == fact.revision) require(old.summary == fact.summary)
    }
    val count = expected.local.count + added
    require(count <= COLLABORATION_LIST_SYNC_CAPACITY)
    return CollaborationListSyncWrite(
        expected.copy(
            local =
                expected.local.copy(
                    revision = expected.local.revision + 1,
                    count = count,
                    acquiredAt = at,
                ),
            published = publish,
            sync = nextState,
        ),
        changed.sortedBy { it.conversationId },
        caughtUp,
        retryAfterSeconds,
    )
}

private fun summaryCursor(value: String) {
    require(value.length in 1..2048 && value.all { it in '!'..'~' })
}

private fun summaryId(value: String) {
    require(Uuid.parse(value).toString() == value)
}

private inline fun summaryPlan(
    block: () -> CollaborationListSyncWrite
): AppResult<CollaborationListSyncWrite> =
    try {
        AppResult.Success(block())
    } catch (_: IllegalArgumentException) {
        AppResult.Failed(
            Failure(
                FailureKind.Unexpected,
                "Conversation information could not be read.",
                code = "client.collaboration_invalid_summary_page",
            )
        )
    }
