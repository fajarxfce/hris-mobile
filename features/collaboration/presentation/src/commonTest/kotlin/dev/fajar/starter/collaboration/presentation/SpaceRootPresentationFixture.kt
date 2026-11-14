package dev.fajar.starter.collaboration.presentation

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ObserveLocalCollaborationSpace
import dev.fajar.starter.collaboration.presentation.navigation.SpaceChatRootOwner
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatThreadFixture
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.*

internal class SpaceRootPresentationFixture {
    val thread = SpaceChatThreadFixture()
    val access = thread.access
    val roots =
        MutableStateFlow<AppResult<CollaborationSpaceRoot?>>(
            AppResult.Success(CollaborationSpaceRoot(access.spaceId))
        )
    var rootReaders = 0
    var gateReaders = 0
    var beforeRootRead: suspend () -> Unit = {}
    var rootSource: () -> Flow<AppResult<CollaborationSpaceRoot?>> = { roots }
    private val repository =
        object : CollaborationSpaceRootRepository {
            override fun observe(access: CollaborationSpaceAccountAccess) = flow {
                rootReaders++
                try {
                    emitAll(rootSource())
                } finally {
                    rootReaders--
                }
            }

            override suspend fun current(
                access: CollaborationSpaceAccountAccess
            ): AppResult<CollaborationSpaceRoot?> {
                beforeRootRead()
                return roots.value
            }

            override suspend fun retain(
                access: CollaborationSpaceAccountAccess,
                root: CollaborationSpaceRoot,
            ): AppResult<Boolean> = error("Presentation cannot publish a Group root")
        }
    private val directory =
        object : CollaborationSpaceDirectoryRepository by thread.directory {
            override fun observeAdmission(access: CollaborationSpaceLocalAccess) = flow {
                gateReaders++
                try {
                    emitAll(thread.gate)
                } finally {
                    gateReaders--
                }
            }
        }

    fun model() =
        SpaceChatRootOwner(
            ObserveLocalCollaborationSpace(
                repository,
                directory,
                thread.base.sessions,
                thread.base.server,
            )
        )
}
