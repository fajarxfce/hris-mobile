package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.*

internal class SpaceRecipientRecoveryFixture {
    val source = SpaceRecipientOutboxFixture()
    val draft = source.draft
    val base = draft.base
    val access = source.access
    val version = CollaborationSpaceRecipientOutboxVersion(base.generation, 0)
    val versions =
        MutableStateFlow<AppResult<CollaborationSpaceRecipientOutboxVersion?>>(
            AppResult.Success(version)
        )
    var readVersions: () -> Flow<AppResult<CollaborationSpaceRecipientOutboxVersion?>> = {
        versions
    }
    var starts = 0
    var stops = 0
    val pages = mutableListOf<Pair<String?, Int>>()
    var page: AppResult<CollaborationSpaceRecipientSubmissionPage> =
        AppResult.Success(
            CollaborationSpaceRecipientSubmissionPage(listOf(source.submission), null)
        )
    var beforePage: suspend () -> Unit = {}
    var afterPage: suspend () -> Unit = {}
    val outbox =
        object : CollaborationSpaceRecipientOutboxRepository by source.outbox {
            override fun observeVersion(access: CollaborationSpaceLocalAccess) = flow {
                assertEquals(this@SpaceRecipientRecoveryFixture.access, access)
                starts++
                try {
                    emitAll(readVersions())
                } finally {
                    stops++
                }
            }

            override suspend fun pending(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationSpaceRecipientSubmissionPage> {
                assertEquals(this@SpaceRecipientRecoveryFixture.access, access)
                pages += after to limit
                beforePage()
                val retained = page
                afterPage()
                return retained
            }
        }

    fun read() =
        ReadCollaborationSpaceRecipientPending(
            outbox,
            draft.directory,
            draft.sessions,
            base.native.server,
        )

    fun observe() =
        ObserveCollaborationSpaceRecipientOutboxVersion(
            outbox,
            draft.directory,
            draft.sessions,
            base.native.server,
        )
}
