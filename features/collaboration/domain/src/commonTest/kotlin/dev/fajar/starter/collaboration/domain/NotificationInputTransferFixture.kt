package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlin.test.*

internal class NotificationInputTransferFixture {
    val existing =
        CollaborationNotificationReplyFixture().apply {
            native.token = CollaborationNotificationActionToken("v2.Zml4dHVyZQ==")
        }
    val reference =
        CollaborationNotificationInputReference(
            "q-" + "a".repeat(64),
            CollaborationReadStateFixture.id(1200),
        )
    val original =
        CollaborationNotificationInput(
            reference,
            CollaborationUnverifiedNotificationReply(
                existing.native.token,
                "Protected private reply",
            ),
        )
    var protected: CollaborationNotificationInput? = original
    var reads = 0
    var copiesRead = 0
    var removals = 0
    var afterRead: suspend () -> Unit = {}
    var beforeRemove: suspend () -> Unit = {}
    var afterRemove: suspend () -> Unit = {}
    var afterCopyRead: suspend () -> Unit = {}
    var forcedRead: AppResult<CollaborationNotificationInput?>? = null
    var forcedCopy: AppResult<CollaborationNotificationReplyInput?>? = null
    var forcedRemove: AppResult<Boolean>? = null
    var copyOutcomeFailure: Failure? = null
    var afterRemovalFailure: Failure? = null
    val inputs =
        object : CollaborationNotificationInputRepository {
            override suspend fun retain(
                reply: CollaborationUnverifiedNotificationReply
            ): AppResult<CollaborationNotificationInputRetention> =
                error("Transfer cannot protect new input")

            override suspend fun page(
                afterId: String?,
                limit: Int,
            ): AppResult<List<CollaborationNotificationInputReference>> =
                error("No implicit discovery")

            override suspend fun get(
                reference: CollaborationNotificationInputReference
            ): AppResult<CollaborationNotificationInput?> {
                assertEquals(this@NotificationInputTransferFixture.reference, reference)
                reads++
                val value = forcedRead ?: AppResult.Success(protected)
                afterRead()
                return value
            }

            override suspend fun remove(
                expected: CollaborationNotificationInput
            ): AppResult<Boolean> {
                beforeRemove()
                removals++
                forcedRemove?.let {
                    return it
                }
                val removed = protected == expected
                if (removed) protected = null
                afterRemove()
                return afterRemovalFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(removed)
            }
        }
    val replies =
        object : CollaborationNotificationReplyRepository by existing.inputs {
            override suspend fun retain(
                access: CollaborationRecoveryAccess,
                candidate: CollaborationNotificationReplyCandidate,
            ): AppResult<CollaborationNotificationReplyRetention> {
                val result = existing.inputs.retain(access, candidate)
                return copyOutcomeFailure?.let { AppResult.Failed(it) } ?: result
            }

            override suspend fun get(
                access: CollaborationRecoveryAccess,
                id: String,
            ): AppResult<CollaborationNotificationReplyInput?> {
                val current = existing.native.local.setup.session!!
                assertEquals(
                    CollaborationRecoveryAccess(
                        current.id,
                        current.binding.audience,
                        current.user.id,
                    ),
                    access,
                )
                assertEquals(existing.saved?.id, id)
                copiesRead++
                val value = forcedCopy ?: AppResult.Success(existing.saved)
                afterCopyRead()
                return value
            }
        }

    fun transfer(platform: AppPlatform = AppPlatform.Android) =
        TransferCollaborationNotificationInput(
            inputs,
            existing.native.actions,
            replies,
            existing.native.local.setup.sessions,
            existing.native.local.server,
            existing.native.local.build.copy(platform = platform),
        )
}
