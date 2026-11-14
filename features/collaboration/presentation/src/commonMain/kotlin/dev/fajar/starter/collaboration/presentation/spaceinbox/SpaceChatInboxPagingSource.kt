package dev.fajar.starter.collaboration.presentation.spaceinbox

import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The retained screen owns publication and reader lifetimes; these are local index notices. */
internal data class SpaceChatInboxPublication(
    val sequence: Long,
    val result: AppResult<CollaborationSpaceInboxVersion?>?,
)

/** Paging's throwable boundary carries a domain failure without logging private content. */
internal class SpaceChatInboxLoadException(val failure: Failure) :
    Exception("Local inbox read failed")

/** One immutable Paging generation. The supplied scope belongs to the retained screen. */
internal class SpaceChatInboxPagingSource(
    scope: CoroutineScope,
    private val publications: StateFlow<SpaceChatInboxPublication>,
    private val read:
        suspend (CollaborationSpaceInboxWindowAnchor, Int) -> AppResult<
                CollaborationSpaceInboxWindow
            >,
) : PagingSource<CollaborationSpaceInboxWindowAnchor, CollaborationSpaceInboxEntry>() {
    private data class Captured(val startedAt: Long, val version: CollaborationSpaceInboxVersion?)

    private val captured = MutableStateFlow<Captured?>(null)
    private val failedAt = MutableStateFlow<Long?>(null)
    private val retired = MutableStateFlow(false)
    private var observation: Job? = null

    init {
        registerInvalidatedCallback {
            retired.value = true
            observation?.cancel()
        }
        observation =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                combine(publications, captured, failedAt) { notice, window, failure ->
                        (failure != null && notice.sequence > failure) ||
                            (window != null &&
                                spaceChatInboxPublicationInvalidates(
                                    window.startedAt,
                                    window.version,
                                    notice,
                                ))
                    }
                    .collect { changed -> if (changed) invalidate() }
            }
        if (invalid) observation?.cancel()
    }

    override fun getRefreshKey(
        state: PagingState<CollaborationSpaceInboxWindowAnchor, CollaborationSpaceInboxEntry>
    ): CollaborationSpaceInboxWindowAnchor? =
        state.anchorPosition
            ?.let { state.closestItemToPosition(it) }
            ?.let { CollaborationSpaceInboxWindowAnchor.Conversation(it.conversationId) }

    override suspend fun load(
        params: LoadParams<CollaborationSpaceInboxWindowAnchor>
    ): LoadResult<CollaborationSpaceInboxWindowAnchor, CollaborationSpaceInboxEntry> {
        currentCoroutineContext().ensureActive()
        if (invalid) return LoadResult.Invalid()
        val anchor = params.key ?: CollaborationSpaceInboxWindowAnchor.Latest
        val requestValid =
            params.loadSize > 0 &&
                when (params) {
                    is LoadParams.Refresh ->
                        anchor is CollaborationSpaceInboxWindowAnchor.Conversation ||
                            anchor == CollaborationSpaceInboxWindowAnchor.Latest
                    is LoadParams.Append -> anchor is CollaborationSpaceInboxWindowAnchor.Older
                    is LoadParams.Prepend -> anchor is CollaborationSpaceInboxWindowAnchor.Newer
                }
        if (!requestValid)
            return LoadResult.Error(
                SpaceChatInboxLoadException(
                    Failure(FailureKind.Validation, "client.collaboration_invalid_request")
                )
            )
        val before = publications.value
        val failure = before.result as? AppResult.Failed
        if (failure != null) {
            failedAt.value = before.sequence
            return LoadResult.Error(SpaceChatInboxLoadException(failure.failure))
        }
        val result = read(anchor, minOf(params.loadSize, 50))
        currentCoroutineContext().ensureActive()
        if (invalid) return LoadResult.Invalid()
        if (result is AppResult.Failed) {
            failedAt.value = before.sequence
            return LoadResult.Error(SpaceChatInboxLoadException(result.failure))
        }
        return when (val window = (result as AppResult.Success).value) {
            CollaborationSpaceInboxWindow.Changed -> {
                // An unchanged Changed result must not make Pager recreate/read indefinitely.
                combine(publications, retired) { notice, stopped ->
                        stopped || notice.sequence > before.sequence
                    }
                    .first { it }
                invalidate()
                LoadResult.Invalid()
            }
            CollaborationSpaceInboxWindow.MissingAnchor -> {
                failedAt.value = before.sequence
                LoadResult.Error(
                    SpaceChatInboxLoadException(
                        Failure(FailureKind.Unexpected, "client.collaboration_invalid_response")
                    )
                )
            }
            is CollaborationSpaceInboxWindow.Items -> acceptWindow(window, before.sequence)
        }
    }

    private fun acceptWindow(
        window: CollaborationSpaceInboxWindow.Items,
        startedAt: Long,
    ): LoadResult<CollaborationSpaceInboxWindowAnchor, CollaborationSpaceInboxEntry> {
        val previous = captured.value
        return if (previous != null && previous.version != window.version) {
            invalidate()
            LoadResult.Invalid()
        } else {
            val current = previous ?: Captured(startedAt, window.version)
            captured.value = current
            val latest = publications.value
            val denied = latest.result as? AppResult.Failed
            if (denied != null) {
                invalidate()
                LoadResult.Error(SpaceChatInboxLoadException(denied.failure))
            } else if (
                invalid ||
                    spaceChatInboxPublicationInvalidates(current.startedAt, current.version, latest)
            ) {
                invalidate()
                LoadResult.Invalid()
            } else {
                failedAt.value = null
                LoadResult.Page(
                    window.entries,
                    window.previous?.let { CollaborationSpaceInboxWindowAnchor.Newer(it) },
                    window.next?.let { CollaborationSpaceInboxWindowAnchor.Older(it) },
                )
            }
        }
    }
}
