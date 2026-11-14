package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSpaceHistoryChanges
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.flow.*

internal class SpaceHistorySignalFixture {
    val base = SpaceDirectoryUseCaseFixture()
    val access = base.access
    val signal = "b4000000-0000-4000-8000-000000000001"
    val next = "b4000000-0000-4000-8000-000000000002"
    val signals = MutableStateFlow<AppResult<String?>>(AppResult.Success(signal))
    var observations = 0
    var stops = 0
    var beforeCurrent: suspend () -> Unit = {}
    var currentGate: suspend () -> AppResult<CollaborationSpaceDirectoryAdmission> = {
        base.directory.admission(access)
    }
    var source: () -> Flow<AppResult<String?>> = { signals }
    val sessions =
        object : SessionRepository by base.sessions {
            override suspend fun current(): AppResult<Session?> {
                beforeCurrent()
                return base.identity.value
            }
        }
    val directory =
        object : CollaborationSpaceDirectoryRepository by base.directory {
            override suspend fun admission(access: CollaborationSpaceLocalAccess) = currentGate()
        }
    val repository =
        object : CollaborationHistorySignalRepository {
            override fun observe(access: CollaborationSpaceLocalAccess) = flow {
                check(access == this@SpaceHistorySignalFixture.access)
                observations++
                try {
                    emitAll(source())
                } finally {
                    stops++
                }
            }
        }

    fun observe() =
        ObserveCollaborationSpaceHistoryChanges(repository, directory, sessions, base.native.server)
}
