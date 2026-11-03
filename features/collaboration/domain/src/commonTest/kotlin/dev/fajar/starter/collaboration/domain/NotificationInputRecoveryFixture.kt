package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*

internal class NotificationInputRecoveryFixture(count: Int = 3) {
    val native = CollaborationNotificationInvocationFixture()
    var references = (1..count).map(::reference)
    var cursor: CollaborationNotificationInputRecoveryCursor? = null
    var reads = 0
    var writes = 0
    val pages = mutableListOf<Pair<String?, Int>>()
    var afterRead: suspend () -> Unit = {}
    var afterPage: suspend () -> Unit = {}
    var beforeWrite: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    var forcedPage: AppResult<List<CollaborationNotificationInputReference>>? = null
    var forcedWrite: AppResult<CollaborationNotificationInputRecoveryCursor?>? = null
    var lostWrite: Failure? = null
    val cursors =
        object : CollaborationNotificationInputRecoveryRepository {
            override suspend fun current(
                access: CollaborationRecoveryAccess
            ): AppResult<CollaborationNotificationInputRecoveryCursor?> {
                reads++
                val result = AppResult.Success(cursor)
                afterRead()
                return result
            }

            override suspend fun replace(
                access: CollaborationRecoveryAccess,
                expected: CollaborationNotificationInputRecoveryCursor?,
                state: CollaborationNotificationInputRecoveryState,
            ): AppResult<CollaborationNotificationInputRecoveryCursor?> {
                beforeWrite()
                writes++
                forcedWrite?.let {
                    return it
                }
                if (cursor != expected) return AppResult.Success(null)
                val next =
                    CollaborationNotificationInputRecoveryCursor(
                        CollaborationReadStateFixture.id(1800 + writes),
                        state,
                    )
                cursor = next
                afterWrite()
                return lostWrite?.let { AppResult.Failed(it) } ?: AppResult.Success(next)
            }
        }
    val inputs =
        object : CollaborationNotificationInputRepository {
            override suspend fun page(
                afterId: String?,
                limit: Int,
            ): AppResult<List<CollaborationNotificationInputReference>> {
                pages += afterId to limit
                val result =
                    forcedPage
                        ?: AppResult.Success(
                            references.filter { afterId == null || it.id > afterId }.take(limit)
                        )
                afterPage()
                return result
            }

            override suspend fun retain(
                reply: CollaborationUnverifiedNotificationReply
            ): AppResult<CollaborationNotificationInputRetention> =
                error("Discovery cannot capture input")

            override suspend fun get(
                reference: CollaborationNotificationInputReference
            ): AppResult<CollaborationNotificationInput?> = error("Discovery cannot decrypt input")

            override suspend fun remove(
                expected: CollaborationNotificationInput
            ): AppResult<Boolean> = error("Progress cannot discard input")
        }

    fun begin() =
        BeginCollaborationNotificationInputRecovery(
            cursors,
            inputs,
            native.local.setup.sessions,
            native.local.server,
            native.local.build.copy(platform = AppPlatform.Android),
        )

    fun advance() =
        AdvanceCollaborationNotificationInputRecovery(
            cursors,
            inputs,
            native.local.setup.sessions,
            native.local.server,
            native.local.build.copy(platform = AppPlatform.Android),
        )

    fun replaceParent() {
        native.local.setup.session =
            native.local.setup.session!!.copy(id = "another-current-session")
    }

    companion object {
        fun reference(index: Int) =
            CollaborationNotificationInputReference(
                "q-" + index.toString(16).padStart(64, '0'),
                CollaborationReadStateFixture.id(1900 + index),
            )
    }
}
