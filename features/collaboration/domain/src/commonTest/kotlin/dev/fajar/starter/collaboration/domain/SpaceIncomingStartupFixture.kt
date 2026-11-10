package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceIncomingStartup
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRootRepository
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSpaceIncomingStartup
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.AppResult
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.*

internal class SpaceIncomingStartupFixture {
    val adoption = SpaceRuntimeAdoptionFixture()
    val setup = adoption.setup
    val owner = setup.owner
    val nextBootstrap =
        adoption.record.copy(
            command =
                adoption.record.command.copy(
                    request = adoption.request.copy(operationId = owner.spaceId)
                )
        )
    val root =
        MutableStateFlow<AppResult<CollaborationSpaceRoot?>>(
            AppResult.Success(CollaborationSpaceRoot(owner.spaceId))
        )
    var rootStarts = 0
    var rootStops = 0
    val roots =
        object : CollaborationSpaceRootRepository {
            override fun observe(access: CollaborationSpaceAccountAccess) = flow {
                assertEquals(owner.parent.localSpaceAccountAccess(), access)
                rootStarts++
                try {
                    emitAll(root)
                } finally {
                    rootStops++
                }
            }

            override suspend fun current(
                access: CollaborationSpaceAccountAccess
            ): AppResult<CollaborationSpaceRoot?> = error("Observation must not poll")

            override suspend fun retain(
                access: CollaborationSpaceAccountAccess,
                root: CollaborationSpaceRoot,
            ): AppResult<Boolean> = error("Observation must not publish roots")
        }

    fun observe(build: AppBuild = setup.build) =
        ObserveCollaborationSpaceIncomingStartup(
            roots,
            setup.registration.commands,
            setup.commands,
            setup.base.directory,
            setup.base.sessions,
            setup.native.server,
            build,
        )

    fun candidate(
        registration: CollaborationSpaceRegistrationRecord? = setup.registered,
        records: List<CollaborationSpaceSetupRecord> = listOf(adoption.record),
        admission: CollaborationSpaceDirectoryAdmission = setup.base.open,
        build: AppBuild = setup.build,
    ) = collaborationSpaceIncomingStartup(owner, build, admission, registration, records)
}
