package dev.fajar.starter.collaboration.presentation

import androidx.paging.PagingSource
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceInboxEntry
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.spaceinbox.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

internal class SpaceInboxPagingFixture(scope: CoroutineScope) {
    val version = CollaborationSpaceInboxVersion(chatId(501), 4, 4)
    val entries = (1..3).map { collaborationSpaceInboxEntry(chatId(it), 1000L - it, null, null) }
    val window = CollaborationSpaceInboxWindow.Items(version, entries, "newer", "older")
    val failure = Failure(FailureKind.Storage, "client.storage_failed")
    val notices = MutableStateFlow(SpaceChatInboxPublication(1, AppResult.Success(version)))
    val calls = mutableListOf<Pair<CollaborationSpaceInboxWindowAnchor, Int>>()
    var read:
        suspend (CollaborationSpaceInboxWindowAnchor) -> AppResult<CollaborationSpaceInboxWindow> =
        {
            AppResult.Success(window)
        }
    val source =
        SpaceChatInboxPagingSource(scope, notices) { anchor, count ->
            calls += anchor to count
            read(anchor)
        }

    fun publish(result: AppResult<CollaborationSpaceInboxVersion?>) {
        notices.value = SpaceChatInboxPublication(notices.value.sequence + 1, result)
    }

    fun refresh(count: Int = 50) =
        PagingSource.LoadParams.Refresh<CollaborationSpaceInboxWindowAnchor>(null, count, false)
}
