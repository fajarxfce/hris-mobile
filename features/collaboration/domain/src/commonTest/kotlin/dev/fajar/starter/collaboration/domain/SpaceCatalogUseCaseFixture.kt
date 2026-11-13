package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceConversationCatalogRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceDirectoryRepository
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.flow.*

internal class SpaceCatalogUseCaseFixture {
    val base = SpaceDirectoryUseCaseFixture()
    val access = base.access
    val version = CollaborationSpaceConversationCatalogVersion(base.generation, 1)
    val pair = listOf(access.accountId, base.native.contact.accountId).sorted()
    val conversation =
        CollaborationSpaceConversationMetadata(
            access.spaceId,
            "80000000-0000-4000-8000-000000000001",
            pair[0],
            pair[1],
            pair[0],
            base.native.setup.now,
            0,
        )
    val page =
        CollaborationSpaceConversationCatalogRead.Page(version, listOf(conversation), null, false)
    val versions =
        MutableStateFlow<AppResult<CollaborationSpaceConversationCatalogVersion?>>(
            AppResult.Success(version)
        )
    val requests = mutableListOf<Pair<String?, Int>>()
    var observations = 0
    var stops = 0
    var beforeCurrent: suspend () -> Unit = {}
    var currentGate: suspend () -> AppResult<CollaborationSpaceDirectoryAdmission> = {
        base.directory.admission(access)
    }
    var onRead: suspend () -> AppResult<CollaborationSpaceConversationCatalogRead> = {
        AppResult.Success(page)
    }
    var source: () -> Flow<AppResult<CollaborationSpaceConversationCatalogVersion?>> = { versions }
    val sessions =
        object : SessionRepository by base.sessions {
            override suspend fun current():
                AppResult<dev.fajar.starter.identity.domain.entities.Session?> {
                beforeCurrent()
                return base.identity.value
            }
        }
    val directory =
        object : CollaborationSpaceDirectoryRepository by base.directory {
            override suspend fun admission(access: CollaborationSpaceLocalAccess) = currentGate()
        }
    val catalog =
        object : CollaborationSpaceConversationCatalogRepository {
            override fun observeVersion(access: CollaborationSpaceLocalAccess) = flow {
                check(access == this@SpaceCatalogUseCaseFixture.access)
                observations++
                try {
                    emitAll(source())
                } finally {
                    stops++
                }
            }

            override suspend fun read(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                rawLimit: Int,
            ): AppResult<CollaborationSpaceConversationCatalogRead> {
                check(access == this@SpaceCatalogUseCaseFixture.access)
                requests += after to rawLimit
                return onRead()
            }
        }

    fun read() =
        ReadCollaborationSpaceConversationCatalog(catalog, directory, sessions, base.native.server)

    fun observe() =
        ObserveCollaborationSpaceConversationCatalog(
            catalog,
            directory,
            sessions,
            base.native.server,
        )
}
