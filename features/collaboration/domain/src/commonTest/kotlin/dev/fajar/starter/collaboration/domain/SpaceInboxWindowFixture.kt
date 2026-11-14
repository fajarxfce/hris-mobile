package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceInboxWindowRepository
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationSpaceInboxWindow
import dev.fajar.starter.common.result.AppResult
import kotlin.test.assertEquals

internal class SpaceInboxWindowFixture {
    val page = SpaceInboxPageFixture()
    val base = page.base
    val access = page.access
    val value = CollaborationSpaceInboxWindow.Items(page.version, listOf(page.entry()), null, null)
    val requests = mutableListOf<Pair<CollaborationSpaceInboxWindowAnchor, Int>>()
    var onRead:
        suspend (CollaborationSpaceInboxWindowAnchor) -> AppResult<CollaborationSpaceInboxWindow> =
        {
            AppResult.Success(value)
        }
    val windows =
        object : CollaborationSpaceInboxWindowRepository {
            override suspend fun read(
                access: CollaborationSpaceLocalAccess,
                anchor: CollaborationSpaceInboxWindowAnchor,
                limit: Int,
            ): AppResult<CollaborationSpaceInboxWindow> {
                assertEquals(this@SpaceInboxWindowFixture.access, access)
                requests += anchor to limit
                return onRead(anchor)
            }
        }
    val read =
        ReadCollaborationSpaceInboxWindow(
            windows,
            base.directory,
            base.sessions,
            base.native.server,
        )
}
