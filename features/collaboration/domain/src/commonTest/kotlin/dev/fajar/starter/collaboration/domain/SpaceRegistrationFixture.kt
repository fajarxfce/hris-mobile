package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.*

internal class SpaceRegistrationFixture {
    val base = SpaceDirectoryUseCaseFixture()
    val native = base.native
    val owner = base.owner
    val access = base.access
    val build = AppBuild(AppPlatform.Android, 5, "test", null)
    val company = "50000000-0000-4000-8000-000000000001"
    val sdkDevice = "80000000-0000-4000-8000-000000000001"
    val receipt = CollaborationSetupReceipt("90000000-0000-4000-8000-000000000001", 0)
    val sdkOwner =
        CollaborationOwner(
            owner.parent.sessionId,
            owner.parent.audience,
            owner.parent.accountId,
            company,
            owner.parent.nativeSessionId,
        )
    val provider =
        CollaborationSpaceIdentity(
            owner.spaceId,
            owner.parent.accountId,
            "matrix.hris.internal",
            "@hris_${company.replace("-", "")}_${owner.parent.accountId.replace("-", "")}:matrix.hris.internal",
            CollaborationSpaceIdentityOrigin.LEGACY,
            company,
            0,
            native.setup.now - 60.seconds,
        )
    val sdkIdentity =
        CollaborationLocalDeviceIdentity(
            provider.matrixUserId,
            "HRIS_${sdkDevice.replace("-", "")}",
            "a".repeat(64),
        )
    var sdkReads = 0
    var identityReads = 0
    var statusReads = 0
    var beforeSdk: suspend () -> AppResult<CollaborationLocalDeviceIdentity> = {
        AppResult.Success(sdkIdentity)
    }
    var beforeIdentity: suspend () -> AppResult<CollaborationSpaceIdentityObservation> = {
        AppResult.Success(CollaborationSpaceIdentityObservation(owner, provider))
    }
    var beforeStatus: suspend () -> AppResult<CollaborationSpaceDeviceObservation> = {
        AppResult.Success(CollaborationSpaceDeviceObservation(owner, null))
    }
    val records =
        MutableStateFlow<AppResult<CollaborationSpaceRegistrationRecord?>>(AppResult.Success(null))
    val sent = mutableListOf<CollaborationSpaceRegistrationCommand>()
    var inserts = 0
    var observations = 0
    var stops = 0
    var beforeCurrent: suspend () -> Unit = {}
    var beforeInsert: suspend () -> Unit = {}
    var beforeUpdate: suspend (CollaborationSpaceRegistrationOutcome) -> AppResult<Boolean> = {
        AppResult.Success(true)
    }
    var beforeRegister: suspend () -> AppResult<CollaborationSetupReceipt> = {
        AppResult.Success(receipt)
    }
    var observeRecords: () -> Flow<AppResult<List<CollaborationSpaceRegistrationRecord>>> = {
        records.map { row ->
            when (row) {
                is AppResult.Failed -> row
                is AppResult.Success -> AppResult.Success(listOfNotNull(row.value))
            }
        }
    }
    val commands =
        object : CollaborationSpaceRegistrationCommandRepository {
            override fun observeAll(access: CollaborationSpaceLocalAccess) = flow {
                observations++
                try {
                    emitAll(observeRecords())
                } finally {
                    stops++
                }
            }

            override fun observe(access: CollaborationSpaceLocalAccess, nativeSessionId: String) =
                observeAll(access).map { rows ->
                    when (rows) {
                        is AppResult.Failed -> rows
                        is AppResult.Success ->
                            AppResult.Success(
                                rows.value.singleOrNull {
                                    it.command.binding.nativeSessionId == nativeSessionId
                                }
                            )
                    }
                }

            override suspend fun current(
                access: CollaborationSpaceLocalAccess,
                nativeSessionId: String,
            ): AppResult<CollaborationSpaceRegistrationRecord?> {
                beforeCurrent()
                return when (val row = records.value) {
                    is AppResult.Failed -> row
                    is AppResult.Success ->
                        AppResult.Success(
                            row.value?.takeIf {
                                it.command.binding.nativeSessionId == nativeSessionId
                            }
                        )
                }
            }

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                command: CollaborationSpaceRegistrationCommand,
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
                        CollaborationSpaceRegistrationRecord(
                            command,
                            0,
                            0,
                            CollaborationSpaceRegistrationOutcome.Pending,
                        )
                    )
                return AppResult.Success(CollaborationSetupInsertion.STORED)
            }

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceRegistrationRecord,
                outcome: CollaborationSpaceRegistrationOutcome,
                attempts: Int,
            ): AppResult<Boolean> {
                val result = beforeUpdate(outcome)
                if (result !is AppResult.Success || !result.value) return result
                if (saved() != expected) return AppResult.Success(false)
                check(validCollaborationSpaceRegistrationTransition(expected, outcome, attempts))
                val next =
                    expected.copy(
                        revision = expected.revision + 1,
                        attempts = attempts,
                        outcome = outcome,
                    )
                check(validCollaborationSpaceRegistrationRecord(access, next))
                records.value = AppResult.Success(next)
                return AppResult.Success(true)
            }
        }
    val identities =
        object : CollaborationSpaceIdentityRepository {
            override suspend fun read(
                owner: CollaborationSpaceOwner
            ): AppResult<CollaborationSpaceIdentityObservation> {
                identityReads++
                return beforeIdentity()
            }

            override suspend fun select(
                owner: CollaborationSpaceOwner,
                command: CollaborationSpaceIdentityCommand,
            ): AppResult<CollaborationSetupReceipt> =
                error("Registration must never auto-select a user")
        }
    val local =
        object : CollaborationLocalDeviceIdentityRepository {
            override suspend fun readLocalIdentity(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<CollaborationLocalDeviceIdentity> {
                check(
                    owner.accountId == sdkOwner.accountId &&
                        owner.companyId == company &&
                        owner.nativeSessionId == sdkOwner.nativeSessionId &&
                        deviceId == sdkDevice
                )
                sdkReads++
                return beforeSdk()
            }
        }
    val devices =
        object : CollaborationSpaceDeviceRepository {
            override suspend fun status(
                owner: CollaborationSpaceOwner
            ): AppResult<CollaborationSpaceDeviceObservation> {
                statusReads++
                return beforeStatus()
            }

            override suspend fun register(
                owner: CollaborationSpaceOwner,
                command: CollaborationSpaceRegistrationCommand,
            ): AppResult<CollaborationSetupReceipt> {
                assertIs<CollaborationSpaceRegistrationOutcome.Unconfirmed>(
                    assertNotNull(saved()).outcome
                )
                sent += command
                return beforeRegister()
            }
        }

    fun saved() =
        assertIs<AppResult.Success<CollaborationSpaceRegistrationRecord?>>(records.value).value

    fun prepare(build: AppBuild = this.build) =
        PrepareCollaborationSpaceRegistration(
            commands,
            identities,
            devices,
            local,
            base.directory,
            base.sessions,
            native.server,
            build,
            native.clock,
            native.time,
        )

    suspend fun prepared() =
        assertIs<AppResult.Success<CollaborationSpaceRegistrationRecord>>(
                prepare()(owner, sdkOwner, sdkDevice, "Phone")
            )
            .value

    fun delivery(build: AppBuild = this.build) =
        ReplayCollaborationSpaceRegistration(
            commands,
            devices,
            local,
            base.directory,
            base.sessions,
            native.server,
            build,
            native.clock,
            native.time,
        )

    fun observe() = ObserveCollaborationSpaceRegistrations(commands, base.sessions, native.server)

    fun session(value: Session) {
        native.setup.session = value
        base.identity.value = AppResult.Success(value)
    }
}
