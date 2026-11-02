package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.CompleteCollaborationNotificationReply
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals

internal class CollaborationNotificationReplyCompletionFixture {
    val retained = CollaborationNotificationReplyFixture()
    val native = retained.native
    val local = native.local
    val expected = retained.input()
    var saved: CollaborationNotificationReplyInput? = expected
    var gets = 0
    var removals = 0
    var inputFailure: Failure? = null
    var messageResult: AppResult<CollaborationMessageRecord?>? = null
    var removeResult: AppResult<Boolean>? = null
    var beforeRemove: suspend () -> Unit = {}
    var afterGet: suspend () -> Unit = {}
    var afterMessage: suspend () -> Unit = {}
    var afterRemove: suspend () -> Unit = {}
    val inputs =
        object : CollaborationNotificationReplyRepository by retained.inputs {
            override suspend fun get(
                access: CollaborationRecoveryAccess,
                id: String,
            ): AppResult<CollaborationNotificationReplyInput?> {
                gets++
                assertEquals(local.setup.session!!.id, access.sessionId)
                assertEquals(expected.id, id)
                val found = saved
                afterGet()
                return inputFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(found)
            }

            override suspend fun remove(
                access: CollaborationRecoveryAccess,
                expected: CollaborationNotificationReplyInput,
            ): AppResult<Boolean> {
                removals++
                beforeRemove()
                val matches = saved == expected
                if (matches) saved = null
                afterRemove()
                return removeResult ?: AppResult.Success(matches)
            }
        }
    val messages =
        object : CollaborationMessageRepository by local.messages {
            override suspend fun get(
                access: CollaborationLocalAccess,
                id: String,
            ): AppResult<CollaborationMessageRecord?> {
                val found = messageResult ?: local.messages.get(access, id)
                afterMessage()
                return found
            }
        }
    val complete = completer()

    fun completer(platform: AppPlatform = AppPlatform.Android) =
        CompleteCollaborationNotificationReply(
            inputs,
            messages,
            local.lists,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build.copy(platform = platform),
            local.clock,
        )

    fun command(state: CollaborationMessageState = CollaborationMessageState.Queued) =
        CollaborationMessageRecord(
            CollaborationMessageIntent(
                expected.candidate.action.operationId,
                native.scope().access.messageOwner(),
                expected.candidate.action.target.conversationId,
                local.clock.now(),
                expected.candidate.text,
            ),
            0,
            state,
        )

    fun seed(state: CollaborationMessageState = CollaborationMessageState.Queued) {
        local.records.value = listOf(command(state))
    }

    suspend fun complete() = complete(expected)

    fun id(value: Int) = CollaborationReadStateFixture.id(value)

    fun device() =
        CollaborationMessageDevice(
            native.parent.nativeSessionId,
            id(800),
            id(801),
            native.target.roomId,
        )
}
