package dev.fajar.starter.collaboration.presentation.setup

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.collaboration.presentation.inbox.ChatInboxFixture
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.participants.ChatParticipantsFixture
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.MutableStateFlow

internal class ChatSetupFixture {
    val base = ChatInboxFixture()
    val owner =
        CollaborationOwner(
            base.initial.id,
            base.server.audience,
            base.initial.user.id,
            chatId(3),
            base.initial.binding.serverId,
        )
    val active =
        CollaborationDeviceSetup(
            chatId(4),
            1,
            CollaborationDeviceState.ACTIVE,
            CollaborationNativePlatform.ANDROID,
            null,
            null,
        )
    var device: CollaborationDeviceSetup? = null
    var record: CollaborationSetupCommandRecord? = null
    val sends = mutableListOf<String>()
    var reads = 0
    var installations = 0
    var restores = 0
    var retired = 0
    var closes = 0
    var opened = false
    var beforeLocal: suspend () -> Unit = {}
    var read: suspend () -> AppResult<CollaborationDeviceSetup?> = { AppResult.Success(device) }
    var register: suspend (CollaborationRegistration) -> AppResult<CollaborationSetupReceipt> = {
        device = active.copy(state = CollaborationDeviceState.PROVISIONING)
        AppResult.Success(CollaborationSetupReceipt(active.id, 0))
    }
    var bootstrap: suspend (CollaborationBootstrap) -> AppResult<CollaborationGateway> = {
        AppResult.Success(gateway())
    }
    var beforeInstall: suspend () -> Unit = {}
    var restore: suspend () -> AppResult<CollaborationRuntimeLease?> = {
        AppResult.Success(if (opened) lease else null)
    }
    val remote =
        object : CollaborationSetupRepository {
            override suspend fun read(
                owner: CollaborationOwner
            ): AppResult<CollaborationDeviceSetup?> {
                reads++
                return read()
            }

            override suspend fun register(
                owner: CollaborationOwner,
                command: CollaborationRegistration,
            ): AppResult<CollaborationSetupReceipt> {
                sends += command.operationId
                return register(command)
            }

            override suspend fun retry(
                owner: CollaborationOwner,
                command: CollaborationProvisioningRetry,
            ): AppResult<CollaborationSetupReceipt> = error("Configure provisioning retry")

            override suspend fun bootstrap(
                owner: CollaborationOwner,
                command: CollaborationBootstrap,
            ): AppResult<CollaborationGateway> {
                sends += command.operationId
                return bootstrap(command)
            }
        }
    val commands =
        object : CollaborationSetupCommandRepository {
            override suspend fun current(
                owner: CollaborationOwner
            ): AppResult<CollaborationSetupCommandRecord?> {
                beforeLocal()
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
                if (record != expected) return AppResult.Success(false)
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
                if (record != expected) return AppResult.Success(false)
                retired++
                record = null
                return AppResult.Success(true)
            }
        }
    val lease =
        object : CollaborationRuntimeLease {
            override val owner
                get() = this@ChatSetupFixture.owner

            override val deviceId
                get() = active.id

            override val installedOperationId
                get() = checkNotNull(installedOperation)

            override val credentialFailures = MutableStateFlow<Failure?>(null)

            override suspend fun retryCredentialWrite(): AppResult<Unit> =
                error("No hidden credential retry")

            override suspend fun close(): AppResult<Unit> {
                closes++
                opened = false
                return AppResult.Success(Unit)
            }
        }
    private var installedOperation: String? = null
    val runtimes =
        object : CollaborationRuntimeRepository {
            override suspend fun isOpen(owner: CollaborationOwner, deviceId: String) =
                AppResult.Success(opened)

            override suspend fun install(
                owner: CollaborationOwner,
                command: CollaborationSetupCommandRecord,
            ): AppResult<CollaborationRuntimeLease> {
                installations++
                installedOperation = command.command.id
                beforeInstall()
                opened = true
                return AppResult.Success(lease)
            }

            override suspend fun restore(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<CollaborationRuntimeLease?> {
                restores++
                return restore()
            }

            override suspend fun closeOtherOwners(
                retainedOwner: CollaborationOwner?
            ): AppResult<Unit> = error("No route closes application owner")

            override suspend fun selfIdentity(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.entities.CollaborationSelfIdentity?
            > = error("No implicit identity acquisition")

            override suspend fun prepareHistoryReopen(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                anchorEventId: String,
            ): AppResult<Boolean> = error("No history")

            override suspend fun readProviderHistory(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                position: String?,
            ): AppResult<CollaborationProviderHistoryPage> = error("No history")

            override suspend fun findHistoryHead(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationHistoryHead> = error("No history")

            override suspend fun readOriginalHistory(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                anchorEventId: String,
            ): AppResult<CollaborationOriginalWindow> = error("No history")

            override suspend fun acquireMessageQueue(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationMessageQueueLease> = error("No delivery")

            override suspend fun enterTimeline(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationTimelineLease> = error("No sync")
        }

    fun gateway() =
        CollaborationGateway(
            owner,
            active.id,
            chatId(5),
            0,
            "@fictional:example.test",
            "fictional-device",
            "fixture-access",
            "fixture-refresh",
            1,
            2,
        )

    fun retained(
        action: CollaborationSetupAction = CollaborationSetupAction.Register("HRIS Android"),
        outcome: CollaborationSetupOutcome = CollaborationSetupOutcome.Unconfirmed(),
    ) =
        CollaborationSetupCommandRecord(
                CollaborationSetupCommand(
                    chatId(6),
                    owner.setupBinding(),
                    CollaborationNativePlatform.ANDROID,
                    base.build.number,
                    base.now,
                    action,
                ),
                1,
                1,
                outcome,
            )
            .also { record = it }

    fun model() =
        ChatSetupViewModel(
            "selection",
            ObserveCollaborationAccess(
                base.local,
                base.sessions,
                base.workspaces,
                base.server,
                base.build,
                base.clock,
            ),
            ReadCollaborationControl(
                commands,
                ChatParticipantsFixture().commands,
                base.local,
                base.sessions,
                base.workspaces,
                base.server,
                base.build,
                base.clock,
            ),
            LoadCollaborationSetup(
                base.sessions,
                base.workspaces,
                remote,
                base.server,
                base.build,
                base.clock,
                base.local,
            ),
            PrepareCollaborationSetup(
                commands,
                remote,
                base.sessions,
                base.workspaces,
                base.server,
                base.build,
                base.clock,
            ),
            ReplayCollaborationSetup(
                commands,
                remote,
                base.sessions,
                base.workspaces,
                base.server,
                base.build,
                base.clock,
            ),
            ReconcileCollaborationSetupReceipt(
                commands,
                remote,
                base.sessions,
                base.workspaces,
                base.server,
                base.build,
                base.clock,
            ),
            InstallCollaborationRuntime(
                commands,
                runtimes,
                base.sessions,
                base.workspaces,
                base.server,
                base.build,
                base.clock,
            ),
            EnsureCollaborationRuntime(
                runtimes,
                base.sessions,
                base.workspaces,
                base.server,
                base.build,
                base.clock,
            ),
            base.build,
        )
}
