package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.time.Clock

internal class CollaborationCommandFixture {
    val access = CollaborationSetupFixture()
    val owner =
        CollaborationOwner(
            access.initial.id,
            access.initial.binding.audience,
            access.initial.user.id,
            access.selected.company.id,
            access.initial.binding.serverId,
        )
    val build = AppBuild(AppPlatform.Android, 5, "test", null)
    val server = IdentityServer(owner.audience, "Fictional Android")
    val clock =
        object : Clock {
            override fun now() = access.now
        }
    var record: CollaborationSetupCommandRecord? = null
    var updateCalls = 0
    var failUpdate: Int? = null
    var loseRevision = false
    var beforeUpdate: suspend () -> Unit = {}
    var beforeCurrent: suspend () -> Unit = {}
    val calls = mutableListOf<Any>()
    val storageFailure = AppResult.Failed(Failure(FailureKind.Storage, "Unavailable storage"))
    var response: suspend () -> AppResult<CollaborationSetupReceipt> = {
        AppResult.Success(CollaborationSetupReceipt("device", 0))
    }
    var gatewayResponse: suspend () -> AppResult<CollaborationGateway> = {
        error("No bootstrap expected")
    }
    val commands =
        object : CollaborationSetupCommandRepository {
            override suspend fun current(
                owner: CollaborationOwner
            ): AppResult<CollaborationSetupCommandRecord?> {
                beforeCurrent()
                return AppResult.Success(record)
            }

            override suspend fun insert(
                owner: CollaborationOwner,
                command: CollaborationSetupCommand,
            ): AppResult<CollaborationSetupInsertion> {
                if (record != null) return AppResult.Success(CollaborationSetupInsertion.OCCUPIED)
                record =
                    CollaborationSetupCommandRecord(
                        command,
                        0,
                        0,
                        CollaborationSetupOutcome.Pending,
                    )
                return AppResult.Success(CollaborationSetupInsertion.STORED)
            }

            override suspend fun update(
                owner: CollaborationOwner,
                expected: CollaborationSetupCommandRecord,
                outcome: CollaborationSetupOutcome,
                attempts: Int,
            ): AppResult<Boolean> {
                updateCalls++
                beforeUpdate()
                if (failUpdate == updateCalls) return storageFailure
                if (
                    loseRevision ||
                        expected.command != record?.command ||
                        expected.revision != record?.revision
                )
                    return AppResult.Success(false)
                record =
                    expected.copy(
                        revision = expected.revision + 1,
                        outcome = outcome,
                        attempts = attempts,
                    )
                return AppResult.Success(true)
            }

            override suspend fun remove(
                owner: CollaborationOwner,
                expected: CollaborationSetupCommandRecord,
            ): AppResult<Boolean> {
                if (expected.command != record?.command || expected.revision != record?.revision)
                    return AppResult.Success(false)
                record = null
                return AppResult.Success(true)
            }
        }
    val remote =
        object : CollaborationSetupRepository by access.remote {
            override suspend fun register(
                owner: CollaborationOwner,
                command: CollaborationRegistration,
            ): AppResult<CollaborationSetupReceipt> {
                calls.add(command)
                return response()
            }

            override suspend fun retry(
                owner: CollaborationOwner,
                command: CollaborationProvisioningRetry,
            ): AppResult<CollaborationSetupReceipt> {
                calls.add(command)
                return response()
            }

            override suspend fun bootstrap(
                owner: CollaborationOwner,
                command: CollaborationBootstrap,
            ): AppResult<CollaborationGateway> {
                calls.add(command)
                return gatewayResponse()
            }
        }

    fun prepare() =
        CollaborationActivationFixture(this, configureDevice = false).operation()::prepareCommand

    fun replay(client: AppBuild = build) =
        CollaborationActivationFixture(this, configureDevice = false).operation(client = client)::
            replayCommand

    fun reconcile() =
        CollaborationActivationFixture(this, configureDevice = false).operation()::reconcileReceipt

    fun retain(
        action: CollaborationSetupAction = CollaborationSetupAction.Register("Fictional phone")
    ): CollaborationSetupCommandRecord {
        val value =
            CollaborationSetupCommandRecord(
                CollaborationSetupCommand(
                    "operation",
                    owner.setupBinding(),
                    CollaborationNativePlatform.ANDROID,
                    5,
                    access.now,
                    action,
                ),
                0,
                0,
                CollaborationSetupOutcome.Pending,
            )
        record = value
        return value
    }
}
