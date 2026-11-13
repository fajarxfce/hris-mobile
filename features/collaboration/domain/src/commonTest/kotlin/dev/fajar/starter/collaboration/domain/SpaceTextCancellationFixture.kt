package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.CancelCollaborationSpaceText
import dev.fajar.starter.common.result.*

internal class SpaceTextCancellationFixture {
    val repair = SpaceInboxRepairUseCaseFixture()
    val base = repair.base
    val access = repair.access
    val expected = repair.record
    var stored: CollaborationSpaceMessageRecord? = expected
    var reads = 0
    val publications = mutableListOf<CollaborationSpaceInboxRepairPublication>()
    var onRead: suspend () -> AppResult<CollaborationSpaceMessageRecord?> = {
        AppResult.Success(stored)
    }
    var onCancel: suspend (CollaborationSpaceInboxRepairPublication) -> AppResult<Boolean> =
        { publication ->
            if (stored != expected || repair.stored != publication.expected)
                AppResult.Success(false)
            else {
                stored = expected.copy(state = CollaborationMessageState.Cancelled)
                repair.stored = publication.replacement
                AppResult.Success(true)
            }
        }
    val messages =
        object : CollaborationSpaceMessageRepository by repair.messages {
            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageRecord?> {
                check(
                    access == this@SpaceTextCancellationFixture.access && id == expected.intent.id
                )
                reads++
                return onRead()
            }
        }
    val cancellations =
        object : CollaborationSpaceMessageCancellationRepository {
            override suspend fun cancel(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceMessageRecord,
                repair: CollaborationSpaceInboxRepairPublication,
            ): AppResult<Boolean> {
                check(
                    access == this@SpaceTextCancellationFixture.access &&
                        expected == this@SpaceTextCancellationFixture.expected
                )
                publications += repair
                return onCancel(repair)
            }
        }

    fun cancel() =
        CancelCollaborationSpaceText(
            messages,
            cancellations,
            repair.progress,
            repair.directory,
            repair.sessions,
            base.native.server,
        )
}
