package dev.fajar.starter.collaboration.presentation.replies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationReplyRepository
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSavedReplies
import dev.fajar.starter.collaboration.presentation.inbox.ChatInboxFixture
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal class ChatUnsentReplyFixture : CollaborationNotificationReplyRepository {
    val inputs = MutableStateFlow(emptyList<CollaborationNotificationReplyInput>())

    fun observer(base: ChatInboxFixture) =
        with(base) {
            ObserveCollaborationSavedReplies(
                this@ChatUnsentReplyFixture,
                local,
                sessions,
                workspaces,
                server,
                build,
                clock,
            )
        }

    fun input(
        base: ChatInboxFixture,
        index: Int = 1,
        conversation: String = base.item(1).conversation.id,
    ) =
        CollaborationNotificationReplyInput(
            "n-" + index.toString(16).padStart(64, '0'),
            CollaborationNotificationReplyRevision(chatId(6), index.toLong()),
            index.toLong(),
            CollaborationNotificationReplyCandidate(
                base.initial.let {
                    CollaborationNativeParent(
                        it.id,
                        it.binding.audience,
                        it.user.id,
                        it.binding.serverId,
                    )
                },
                CollaborationNotificationAction.Reply(
                    chatId(7 + index),
                    CollaborationNotificationActionTarget(
                        chatId(3),
                        conversation,
                        "!room:example.test",
                        "\$event",
                        chatId(8),
                        chatId(9),
                    ),
                ),
                CollaborationNotificationActionToken("v1.fixture"),
                "Pesan tertahan $index",
            ),
            CollaborationNotificationReplyReview.SessionChanged,
        )

    override fun observeChanges(access: CollaborationRecoveryAccess) =
        inputs.map { AppResult.Success(Unit) }

    override suspend fun page(
        access: CollaborationRecoveryAccess,
        after: Long?,
        limit: Int,
        pendingOnly: Boolean,
    ): AppResult<CollaborationNotificationReplyPage> {
        val rows = inputs.value.filter { after == null || it.sequence > after }.take(limit)
        return AppResult.Success(
            CollaborationNotificationReplyPage(
                rows,
                if (rows.size == limit) rows.last().sequence else null,
            )
        )
    }

    override suspend fun get(access: CollaborationRecoveryAccess, id: String) =
        AppResult.Success(inputs.value.find { it.id == id })

    override suspend fun retain(
        access: CollaborationRecoveryAccess,
        candidate: CollaborationNotificationReplyCandidate,
    ): AppResult<CollaborationNotificationReplyRetention> = error("Read only fixture")

    override suspend fun hold(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationReplyInput,
        reason: CollaborationNotificationReplyReview,
    ): AppResult<Boolean> = error("Read only fixture")

    override suspend fun remove(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationReplyInput,
    ): AppResult<Boolean> = error("Read only fixture")
}
