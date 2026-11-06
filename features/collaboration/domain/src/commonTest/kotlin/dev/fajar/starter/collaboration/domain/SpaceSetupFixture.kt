package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.*

internal class SpaceSetupFixture {
    val registration = SpaceRegistrationFixture()
    val base = registration.base
    val native = registration.native
    val owner = registration.owner
    val access = registration.access
    val build = registration.build
    val registrationId = "40000000-0000-4000-8000-000000000099"
    val registered =
        CollaborationSpaceRegistrationRecord(
            CollaborationSpaceRegistrationCommand(
                registrationId,
                CollaborationSpaceDeviceBinding(
                    owner.parent.audience,
                    owner.parent.accountId,
                    owner.spaceId,
                    owner.parent.nativeSessionId,
                ),
                CollaborationNativePlatform.ANDROID,
                4,
                native.setup.now - 120.seconds,
                registration.provider,
                "Phone",
                CollaborationSpaceDeviceOrigin.LEGACY,
                CollaborationSpaceLegacySdk(
                    registration.sdkOwner.setupBinding(),
                    registration.sdkDevice,
                    registration.sdkIdentity,
                ),
            ),
            2,
            1,
            CollaborationSpaceRegistrationOutcome.Registered(registration.receipt),
        )
    var metadataOwner = owner
    var status = failedStatus()
    val records =
        MutableStateFlow<AppResult<CollaborationSpaceSetupRecord?>>(AppResult.Success(null))
    val archives = mutableMapOf<String, CollaborationSpaceSetupRecord>()
    val sent = mutableListOf<CollaborationSpaceSetupRequest>()
    var inserts = 0
    var archiveWrites = 0
    var observations = 0
    var stops = 0
    var beforeRead: suspend () -> Unit = {}
    var beforeInsert: suspend () -> AppResult<CollaborationSetupInsertion>? = { null }
    var beforeUpdate: suspend (CollaborationSpaceSetupOutcome) -> AppResult<Boolean> = {
        AppResult.Success(true)
    }
    var beforeHandoff: suspend () -> AppResult<Boolean> = { AppResult.Success(true) }
    var afterHandoff: suspend () -> AppResult<Boolean> = { AppResult.Success(true) }
    var beforeReplacement:
        suspend (CollaborationSpaceBootstrapReplacement) -> AppResult<
                CollaborationSpaceBootstrapReplacementReceipt
            > =
        {
            AppResult.Success(
                CollaborationSpaceBootstrapReplacementReceipt(
                    it.supersedes,
                    it.operationId,
                    gateway(it.replacement),
                )
            )
        }
    var beforeArchive: suspend () -> AppResult<Boolean> = { AppResult.Success(true) }
    var beforeRetry:
        suspend (CollaborationSpaceProvisioningRetry) -> AppResult<CollaborationSetupReceipt> =
        {
            AppResult.Success(CollaborationSetupReceipt(it.deviceId, it.provisioningRevision + 1))
        }
    var beforeBootstrap:
        suspend (CollaborationSpaceBootstrap) -> AppResult<CollaborationSpaceGateway> =
        {
            AppResult.Success(gateway(it))
        }
    var observeRecords: () -> Flow<AppResult<List<CollaborationSpaceSetupRecord>>> = {
        records.map {
            when (it) {
                is AppResult.Failed -> it
                is AppResult.Success -> AppResult.Success(listOfNotNull(it.value))
            }
        }
    }

    init {
        registration.records.value = AppResult.Success(registered)
        registration.beforeIdentity = {
            AppResult.Success(
                CollaborationSpaceIdentityObservation(metadataOwner, registration.provider)
            )
        }
        registration.beforeStatus = {
            AppResult.Success(CollaborationSpaceDeviceObservation(metadataOwner, status))
        }
        registration.beforeSdk = { error("Native setup must not reopen an SDK") }
    }

    fun failedStatus(revision: Long = 1) =
        CollaborationSpaceDeviceStatus(
            registration.receipt.id,
            0,
            CollaborationDeviceState.PROVISIONING,
            CollaborationNativePlatform.ANDROID,
            CollaborationSpaceDeviceOrigin.LEGACY,
            "Phone",
            CollaborationSpaceProvisioningStatus(
                revision,
                CollaborationProvisioningState.FAILED,
                1,
                native.setup.now - 1.seconds,
                CollaborationSpaceProvisioningOutcome(
                    revision,
                    CollaborationProvisioningState.FAILED,
                    native.setup.now - 2.seconds,
                    CollaborationSpaceProvisioningFailure.PROVIDER_UNAVAILABLE,
                ),
                native.setup.now - 1.seconds,
            ),
            native.setup.now,
            null,
        )

    fun activeStatus(revision: Long = 2) =
        CollaborationSpaceDeviceStatus(
            registration.receipt.id,
            1,
            CollaborationDeviceState.ACTIVE,
            CollaborationNativePlatform.ANDROID,
            CollaborationSpaceDeviceOrigin.LEGACY,
            "Phone",
            CollaborationSpaceProvisioningStatus(
                revision,
                CollaborationProvisioningState.SUCCEEDED,
                1,
                native.setup.now + 60.seconds,
                CollaborationSpaceProvisioningOutcome(
                    revision,
                    CollaborationProvisioningState.SUCCEEDED,
                    native.setup.now,
                    null,
                ),
                null,
            ),
            native.setup.now,
            null,
        )

    fun gateway(request: CollaborationSpaceBootstrap): CollaborationSpaceGateway {
        val hard =
            minOf(
                (native.setup.now + 120.seconds).toEpochMilliseconds(),
                (native.setup.session ?: native.session).binding.expiresAtEpochMillis,
            )
        return CollaborationSpaceGateway(
            request.binding,
            request.deviceId,
            "a0000000-0000-4000-8000-000000000001",
            (request.gatewayVersion ?: -1) + 1,
            request.provider.matrixUserId,
            request.matrixDeviceId,
            "hris_mxg_a_" + "a".repeat(43),
            "hris_mxg_r_" + "b".repeat(43),
            minOf((native.setup.now + 60.seconds).toEpochMilliseconds(), hard),
            hard,
        )
    }

    val commands =
        object : CollaborationSpaceSetupCommandRepository {
            override fun observeAll(access: CollaborationSpaceLocalAccess) = flow {
                observations++
                try {
                    emitAll(observeRecords())
                } finally {
                    stops++
                }
            }

            override suspend fun current(
                access: CollaborationSpaceLocalAccess,
                nativeSessionId: String,
            ): AppResult<CollaborationSpaceSetupRecord?> {
                beforeRead()
                return when (val value = records.value) {
                    is AppResult.Failed -> value
                    is AppResult.Success ->
                        AppResult.Success(
                            value.value?.takeIf {
                                it.command.binding.nativeSessionId == nativeSessionId
                            }
                        )
                }
            }

            override suspend fun find(
                access: CollaborationSpaceLocalAccess,
                commandId: String,
            ): AppResult<CollaborationSpaceSetupRecord?> {
                beforeRead()
                return when (val value = records.value) {
                    is AppResult.Failed -> value
                    is AppResult.Success ->
                        AppResult.Success(
                            value.value?.takeIf { it.command.id == commandId }
                                ?: archives[commandId]
                        )
                }
            }

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                command: CollaborationSpaceSetupCommand,
            ): AppResult<CollaborationSetupInsertion> {
                inserts++
                beforeInsert()?.let {
                    return it
                }
                val current = saved()
                if (current != null)
                    return AppResult.Success(
                        if (current.command == command) CollaborationSetupInsertion.STORED
                        else CollaborationSetupInsertion.OCCUPIED
                    )
                records.value =
                    AppResult.Success(
                        CollaborationSpaceSetupRecord(
                            command,
                            0,
                            0,
                            CollaborationSpaceSetupOutcome.Pending,
                        )
                    )
                return AppResult.Success(CollaborationSetupInsertion.STORED)
            }

            override suspend fun prepareReplacement(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceSetupRecord,
                replacement: CollaborationSpaceSetupCommand,
            ): AppResult<Boolean> {
                val result = beforeHandoff()
                if (result !is AppResult.Success || !result.value) return result
                if (saved() != expected) return AppResult.Success(false)
                check(validCollaborationSpaceSetupHandoff(access, expected, replacement))
                archives[expected.command.id] = expected.copy(successorId = replacement.id)
                records.value =
                    AppResult.Success(
                        CollaborationSpaceSetupRecord(
                            replacement,
                            0,
                            0,
                            CollaborationSpaceSetupOutcome.Pending,
                        )
                    )
                return afterHandoff()
            }

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceSetupRecord,
                outcome: CollaborationSpaceSetupOutcome,
                attempts: Int,
            ): AppResult<Boolean> {
                val result = beforeUpdate(outcome)
                if (result !is AppResult.Success || !result.value) return result
                if (saved() != expected) return AppResult.Success(false)
                check(validCollaborationSpaceSetupTransition(expected, outcome, attempts))
                val next =
                    expected.copy(
                        revision = expected.revision + 1,
                        attempts = attempts,
                        outcome = outcome,
                    )
                check(validCollaborationSpaceSetupRecord(access, next))
                records.value = AppResult.Success(next)
                return AppResult.Success(true)
            }

            override suspend fun archiveRetried(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceSetupRecord,
            ): AppResult<Boolean> {
                val result = beforeArchive()
                if (result !is AppResult.Success || !result.value) return result
                if (archives[expected.command.id] == expected) return AppResult.Success(true)
                if (saved() != expected) return AppResult.Success(false)
                check(expected.outcome is CollaborationSpaceSetupOutcome.Retried)
                archiveWrites++
                archives[expected.command.id] = expected
                records.value = AppResult.Success(null)
                return AppResult.Success(true)
            }
        }
    val remote =
        object : CollaborationSpaceSetupRepository {
            override suspend fun replaceBootstrap(
                owner: CollaborationSpaceOwner,
                command: CollaborationSpaceBootstrapReplacement,
            ): AppResult<CollaborationSpaceBootstrapReplacementReceipt> {
                assertIs<CollaborationSpaceSetupOutcome.Unconfirmed>(assertNotNull(saved()).outcome)
                assertEquals(saved()?.command?.request, command)
                sent += command
                return beforeReplacement(command)
            }

            override suspend fun retry(
                owner: CollaborationSpaceOwner,
                command: CollaborationSpaceProvisioningRetry,
            ): AppResult<CollaborationSetupReceipt> {
                assertIs<CollaborationSpaceSetupOutcome.Unconfirmed>(assertNotNull(saved()).outcome)
                assertEquals(saved()?.command?.request, command)
                sent += command
                return beforeRetry(command)
            }

            override suspend fun bootstrap(
                owner: CollaborationSpaceOwner,
                command: CollaborationSpaceBootstrap,
            ): AppResult<CollaborationSpaceGateway> {
                assertIs<CollaborationSpaceSetupOutcome.Unconfirmed>(assertNotNull(saved()).outcome)
                assertEquals(saved()?.command?.request, command)
                sent += command
                return beforeBootstrap(command)
            }
        }

    fun saved() = assertIs<AppResult.Success<CollaborationSpaceSetupRecord?>>(records.value).value

    fun prepare(build: AppBuild = this.build) =
        PrepareCollaborationSpaceSetup(
            commands,
            registration.commands,
            registration.identities,
            registration.devices,
            base.directory,
            base.sessions,
            native.server,
            build,
            native.clock,
            native.time,
        )

    fun replace(build: AppBuild = this.build) =
        PrepareCollaborationSpaceBootstrapReplacement(
            commands,
            registration.commands,
            registration.identities,
            registration.devices,
            base.directory,
            base.sessions,
            native.server,
            build,
            native.clock,
            native.time,
        )

    fun delivery(build: AppBuild = this.build) =
        ReplayCollaborationSpaceSetup(
            commands,
            registration.commands,
            registration.identities,
            registration.devices,
            remote,
            base.directory,
            base.sessions,
            native.server,
            build,
            native.clock,
            native.time,
        )

    fun reconcile(build: AppBuild = this.build) =
        ReconcileCollaborationSpaceRetry(
            commands,
            registration.commands,
            registration.devices,
            base.directory,
            base.sessions,
            native.server,
            build,
            native.clock,
            native.time,
        )

    fun observe() = ObserveCollaborationSpaceSetup(commands, base.sessions, native.server)

    suspend fun prepared(
        kind: CollaborationSpaceSetupKind = CollaborationSpaceSetupKind.RETRY
    ): CollaborationSpaceSetupRecord {
        if (kind == CollaborationSpaceSetupKind.BOOTSTRAP) status = activeStatus()
        return assertIs<AppResult.Success<CollaborationSpaceSetupRecord>>(
                prepare()(owner, registrationId, kind)
            )
            .value
    }
}
