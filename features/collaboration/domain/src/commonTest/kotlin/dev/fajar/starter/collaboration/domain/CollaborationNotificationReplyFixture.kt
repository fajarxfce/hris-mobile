package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationReplyRepository
import dev.fajar.starter.collaboration.domain.usecases.RetainCollaborationNotificationReply
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*

internal class CollaborationNotificationReplyFixture {
    val native = CollaborationNotificationInvocationFixture()
    var saved: CollaborationNotificationReplyInput? = null
    var stores = 0
    var forced: AppResult<CollaborationNotificationReplyRetention>? = null
    var afterStore: suspend () -> Unit = {}
    val inputs =
        object : CollaborationNotificationReplyRepository {
            override suspend fun retain(
                access: CollaborationRecoveryAccess,
                candidate: CollaborationNotificationReplyCandidate,
            ): AppResult<CollaborationNotificationReplyRetention> {
                val current = native.local.setup.session!!
                kotlin.test.assertEquals(
                    CollaborationRecoveryAccess(
                        current.id,
                        current.binding.audience,
                        current.user.id,
                    ),
                    access,
                )
                stores++
                forced?.let {
                    afterStore()
                    return it
                }
                val input =
                    saved?.takeIf { it.candidate.copy(token = candidate.token) == candidate }
                        ?: input(candidate)
                saved = input
                afterStore()
                return AppResult.Success(CollaborationNotificationReplyRetention.Retained(input))
            }

            override suspend fun get(
                access: CollaborationRecoveryAccess,
                id: String,
            ): AppResult<CollaborationNotificationReplyInput?> = error("No unrelated input reads")

            override suspend fun page(
                access: CollaborationRecoveryAccess,
                after: Long?,
                limit: Int,
                pendingOnly: Boolean,
            ): AppResult<CollaborationNotificationReplyPage> = error("No discovery")

            override suspend fun hold(
                access: CollaborationRecoveryAccess,
                expected: CollaborationNotificationReplyInput,
                reason: CollaborationNotificationReplyReview,
            ): AppResult<Boolean> = error("Retention does not review")

            override suspend fun remove(
                access: CollaborationRecoveryAccess,
                expected: CollaborationNotificationReplyInput,
            ): AppResult<Boolean> = error("Retention never deletes input")
        }
    val retain = retainer()

    fun retainer(platform: AppPlatform = AppPlatform.Android) =
        RetainCollaborationNotificationReply(
            native.actions,
            inputs,
            native.local.setup.sessions,
            native.local.server,
            native.local.build.copy(platform = platform),
        )

    fun candidate(text: String = "Private typed reply") =
        CollaborationNotificationReplyCandidate(
            native.parent,
            native.action as CollaborationNotificationAction.Reply,
            native.token,
            text,
        )

    fun input(candidate: CollaborationNotificationReplyCandidate = candidate()) =
        CollaborationNotificationReplyInput(
            "n-" + "a".repeat(64),
            CollaborationNotificationReplyRevision(CollaborationReadStateFixture.id(990), 0),
            0,
            candidate,
        )

    suspend fun retain(text: String = "Private typed reply") = retain(native.token, text)
}
