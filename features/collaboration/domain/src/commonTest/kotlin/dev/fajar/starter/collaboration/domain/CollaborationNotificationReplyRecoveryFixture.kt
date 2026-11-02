package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals

internal class CollaborationNotificationReplyRecoveryFixture {
    val base = CollaborationNotificationReplyFixture()
    val native = base.native
    val local = native.local
    var records = (1..6).map(::input)
    var cursor: CollaborationNotificationReplyRecoveryCursor? = null
    var pageResult: AppResult<CollaborationNotificationReplyPage>? = null
    var cursorResult: AppResult<CollaborationNotificationReplyRecoveryCursor?>? = null
    var advanceResult: AppResult<CollaborationNotificationReplyRecoveryCursor?>? = null
    var afterCursor: suspend () -> Unit = {}
    var afterPage: suspend () -> Unit = {}
    var afterAdvance: suspend () -> Unit = {}
    val pageAfters = mutableListOf<Long?>()
    val pageLimits = mutableListOf<Int>()
    var cursorReads = 0
    var advances = 0
    var sequence = 7000
    val inputs =
        object : CollaborationNotificationReplyRepository by base.inputs {
            override suspend fun page(
                access: CollaborationRecoveryAccess,
                after: Long?,
                limit: Int,
                pendingOnly: Boolean,
            ): AppResult<CollaborationNotificationReplyPage> {
                check(limit == 1 || limit == 4)
                pageLimits += limit
                assertEquals(true, pendingOnly)
                assertEquals(local.setup.session!!.id, access.sessionId)
                pageAfters += after
                val selected =
                    records.filter { it.review == null && (after == null || it.sequence > after) }
                val result =
                    pageResult
                        ?: AppResult.Success(
                            CollaborationNotificationReplyPage(
                                selected.take(limit),
                                if (selected.size > limit) selected[limit - 1].sequence else null,
                            )
                        )
                afterPage()
                return result
            }
        }
    val cursors =
        object : CollaborationNotificationReplyRecoveryCursorRepository {
            override suspend fun current(
                access: CollaborationRecoveryAccess
            ): AppResult<CollaborationNotificationReplyRecoveryCursor?> {
                cursorReads++
                val result = cursorResult ?: AppResult.Success(cursor)
                afterCursor()
                return result
            }

            override suspend fun advance(
                access: CollaborationRecoveryAccess,
                expected: CollaborationNotificationReplyRecoveryCursor?,
                afterSequence: Long,
            ): AppResult<CollaborationNotificationReplyRecoveryCursor?> =
                replace(access, expected, afterSequence, null)

            override suspend fun replace(
                access: CollaborationRecoveryAccess,
                expected: CollaborationNotificationReplyRecoveryCursor?,
                afterSequence: Long,
                cycle: CollaborationNotificationReplyRecoveryCycle?,
            ): AppResult<CollaborationNotificationReplyRecoveryCursor?> {
                advances++
                val changed =
                    if (cursor == expected)
                        CollaborationNotificationReplyRecoveryCursor(
                            id(sequence++),
                            afterSequence,
                            cycle,
                        )
                    else null
                if (changed != null) cursor = changed
                afterAdvance()
                return advanceResult ?: AppResult.Success(changed)
            }
        }

    fun reader(platform: AppPlatform = AppPlatform.Android) =
        ReadCollaborationNotificationReplyRecovery(
            inputs,
            cursors,
            local.setup.sessions,
            local.server,
            local.build.copy(platform = platform),
        )

    val advance =
        AdvanceCollaborationNotificationReplyRecovery(
            cursors,
            local.setup.sessions,
            local.server,
            local.build,
        )

    suspend fun read() = reader()()

    fun input(index: Int) =
        base.input().let { first ->
            first.copy(
                id = "n-" + index.toString(16).padStart(64, '0'),
                version = first.version.copy(revision = index.toLong()),
                sequence = index.toLong(),
                candidate =
                    first.candidate.copy(
                        text = "Fictional reply $index",
                        action = first.candidate.action.copy(operationId = id(8000 + index)),
                    ),
            )
        }

    fun id(index: Int) = CollaborationReadStateFixture.id(index)
}
