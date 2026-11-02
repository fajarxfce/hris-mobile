package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*

internal class CollaborationNotificationReplyReviewFixture {
    val base = CollaborationNotificationReplyCompletionFixture()
    val local = base.local
    val native = base.native
    val expected = base.expected
    var holds = 0
    var beforeHold: suspend () -> Unit = {}
    var afterHold: suspend () -> Unit = {}
    var holdFailure: Failure? = null
    val inputs =
        object : CollaborationNotificationReplyRepository by base.inputs {
            override suspend fun hold(
                access: CollaborationRecoveryAccess,
                expected: CollaborationNotificationReplyInput,
                reason: CollaborationNotificationReplyReview,
            ): AppResult<Boolean> {
                holds++
                beforeHold()
                val matches = base.saved == expected
                if (matches)
                    base.saved =
                        expected.copy(
                            version =
                                expected.version.copy(revision = expected.version.revision + 1),
                            review = reason,
                        )
                afterHold()
                return holdFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(matches)
            }
        }
    val reviewer = makeReviewer()

    fun reader(platform: AppPlatform = AppPlatform.Android) =
        ReadCollaborationNotificationReply(
            inputs,
            local.lists,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build.copy(platform = platform),
            local.clock,
        )

    fun makeReviewer(platform: AppPlatform = AppPlatform.Android) =
        ReviewCollaborationNotificationReply(
            inputs,
            base.messages,
            local.lists,
            native.read.base.history,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build.copy(platform = platform),
            local.clock,
        )

    suspend fun review(input: CollaborationNotificationReplyInput = expected) = reviewer(input)

    suspend fun read() = reader()(expected.id)

    fun conflict() {
        base.messageResult =
            AppResult.Success(
                base.command().let {
                    it.copy(intent = it.intent.copy(text = "Different retained command"))
                }
            )
    }
}
