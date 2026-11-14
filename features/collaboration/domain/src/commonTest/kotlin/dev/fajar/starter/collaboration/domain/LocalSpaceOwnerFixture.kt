package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ObserveLocalCollaborationSpace
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.flow.*

internal class LocalSpaceOwnerFixture {
    val base = SpaceThreadHistoryFixture()
    val access = base.access
    val account =
        CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
    val other = "d5000000-0000-4000-8000-000000000001"
    val identity = MutableStateFlow<AppResult<Session?>>(AppResult.Success(base.native.session))
    val facts =
        MutableStateFlow<AppResult<CollaborationSpaceRoot?>>(
            AppResult.Success(CollaborationSpaceRoot(access.spaceId))
        )
    val allowed = CollaborationSpaceDirectoryAdmission(null, null)
    val denied = Failure(FailureKind.AccessDenied, "Fixture denied")
    val unavailable = Failure(FailureKind.Storage, "Fixture storage failure")
    val admission =
        MutableStateFlow<AppResult<CollaborationSpaceDirectoryAdmission>>(
            AppResult.Success(allowed)
        )
    val rootStarts = mutableListOf<CollaborationSpaceAccountAccess>()
    val rootStops = mutableListOf<CollaborationSpaceAccountAccess>()
    val gateStarts = mutableListOf<CollaborationSpaceLocalAccess>()
    val gateStops = mutableListOf<CollaborationSpaceLocalAccess>()
    var sessionStarts = 0
    var sessionStops = 0
    var rootReads = 0
    var gateReads = 0
    var rootSource: (CollaborationSpaceAccountAccess) -> Flow<AppResult<CollaborationSpaceRoot?>> =
        {
            facts
        }
    var gateSource:
        (CollaborationSpaceLocalAccess) -> Flow<AppResult<CollaborationSpaceDirectoryAdmission>> =
        {
            admission
        }
    var beforeRootRead: suspend () -> Unit = {}
    var beforeGateRead: suspend () -> Unit = {}
    var beforeSessionRead: suspend () -> Unit = {}
    val sessions =
        object : SessionRepository by base.sessions {
            override fun observe() = flow {
                sessionStarts++
                try {
                    emitAll(identity)
                } finally {
                    sessionStops++
                }
            }

            override suspend fun current(): AppResult<Session?> {
                beforeSessionRead()
                return identity.value
            }
        }
    val roots =
        object : CollaborationSpaceRootRepository {
            override fun observe(access: CollaborationSpaceAccountAccess) = flow {
                rootStarts += access
                try {
                    emitAll(rootSource(access))
                } finally {
                    rootStops += access
                }
            }

            override suspend fun current(
                access: CollaborationSpaceAccountAccess
            ): AppResult<CollaborationSpaceRoot?> {
                rootReads++
                beforeRootRead()
                return if (access == account) facts.value else AppResult.Success(null)
            }

            override suspend fun retain(
                access: CollaborationSpaceAccountAccess,
                root: CollaborationSpaceRoot,
            ): AppResult<Boolean> = error("No root publication")
        }
    val directory =
        object : CollaborationSpaceDirectoryRepository by base.directory {
            override fun observeAdmission(access: CollaborationSpaceLocalAccess) = flow {
                gateStarts += access
                try {
                    emitAll(gateSource(access))
                } finally {
                    gateStops += access
                }
            }

            override suspend fun admission(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceDirectoryAdmission> {
                gateReads++
                beforeGateRead()
                return admission.value
            }
        }

    fun observe() = ObserveLocalCollaborationSpace(roots, directory, sessions, base.native.server)()
}
