package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import kotlin.test.*
import kotlinx.coroutines.flow.*

internal class SpaceIdentityCommandFixture {
    val base = SpaceDirectoryUseCaseFixture()
    val native = base.native
    val owner = base.owner
    val access = base.access
    val build = AppBuild(AppPlatform.Android, 5, "test", null)
    val records =
        MutableStateFlow<AppResult<CollaborationSpaceIdentityCommandRecord?>>(
            AppResult.Success(null)
        )
    val sent = mutableListOf<CollaborationSpaceIdentityCommand>()
    var inserts = 0
    var observations = 0
    var stops = 0
    var beforeCurrent: suspend () -> Unit = {}
    var beforeInsert: suspend () -> Unit = {}
    var beforeUpdate: suspend (CollaborationSpaceIdentityOutcome) -> AppResult<Boolean> = {
        AppResult.Success(true)
    }
    var beforeSelect: suspend () -> AppResult<CollaborationSetupReceipt> = {
        AppResult.Success(CollaborationSetupReceipt(owner.parent.accountId, 0))
    }
    var observeRecords: () -> Flow<AppResult<CollaborationSpaceIdentityCommandRecord?>> = {
        records
    }
    val commands =
        object : CollaborationSpaceIdentityCommandRepository {
            override fun observe(access: CollaborationSpaceLocalAccess) = flow {
                observations++
                try {
                    emitAll(observeRecords())
                } finally {
                    stops++
                }
            }

            override suspend fun current(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceIdentityCommandRecord?> {
                beforeCurrent()
                return records.value
            }

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                command: CollaborationSpaceIdentityCommand,
            ): AppResult<CollaborationSetupInsertion> {
                inserts++
                beforeInsert()
                val previous = saved()
                if (previous != null)
                    return AppResult.Success(
                        if (previous.command == command) CollaborationSetupInsertion.STORED
                        else CollaborationSetupInsertion.OCCUPIED
                    )
                records.value =
                    AppResult.Success(
                        CollaborationSpaceIdentityCommandRecord(
                            command,
                            0,
                            0,
                            CollaborationSpaceIdentityOutcome.Pending,
                        )
                    )
                return AppResult.Success(CollaborationSetupInsertion.STORED)
            }

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceIdentityCommandRecord,
                outcome: CollaborationSpaceIdentityOutcome,
                attempts: Int,
            ): AppResult<Boolean> {
                val result = beforeUpdate(outcome)
                if (result !is AppResult.Success || !result.value) return result
                if (saved() != expected) return AppResult.Success(false)
                check(validCollaborationSpaceIdentityTransition(expected, outcome, attempts))
                val next =
                    expected.copy(
                        revision = expected.revision + 1,
                        attempts = attempts,
                        outcome = outcome,
                    )
                check(validCollaborationSpaceIdentityRecord(access, next))
                records.value = AppResult.Success(next)
                return AppResult.Success(true)
            }
        }
    val identities =
        object : CollaborationSpaceIdentityRepository {
            override suspend fun read(
                owner: CollaborationSpaceOwner
            ): AppResult<CollaborationSpaceIdentityObservation> =
                error("Selection commands must not read or auto-select identities")

            override suspend fun select(
                owner: CollaborationSpaceOwner,
                command: CollaborationSpaceIdentityCommand,
            ): AppResult<CollaborationSetupReceipt> {
                assertIs<CollaborationSpaceIdentityOutcome.Unconfirmed>(
                    assertNotNull(saved()).outcome
                )
                sent += command
                return beforeSelect()
            }
        }

    fun saved() =
        assertIs<AppResult.Success<CollaborationSpaceIdentityCommandRecord?>>(records.value).value

    suspend fun discovery() =
        assertIs<AppResult.Success<CollaborationSpaceDiscovery>>(native.discovery()(native.parent))
            .value

    fun prepare() =
        PrepareCollaborationSpaceIdentity(
            commands,
            base.sessions,
            native.server,
            build,
            native.clock,
        )

    suspend fun prepared() =
        assertIs<AppResult.Success<CollaborationSpaceIdentityCommandRecord>>(prepare()(discovery()))
            .value

    fun delivery(build: AppBuild = this.build) =
        ReplayCollaborationSpaceIdentity(
            commands,
            identities,
            base.directory,
            base.sessions,
            native.server,
            build,
            native.clock,
            native.time,
        )

    fun observe() = ObserveCollaborationSpaceIdentityCommand(commands, base.sessions, native.server)

    fun session(value: Session) {
        native.setup.session = value
        base.identity.value = AppResult.Success(value)
    }
}
