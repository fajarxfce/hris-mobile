@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One finite setup workflow. Enrollment, retained commands and publication share one owner. */
class ActivateCollaborationDevice(
    private val commands: CollaborationSetupCommandRepository,
    private val setup: CollaborationSetupRepository,
    private val runtimes: CollaborationRuntimeRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val replicas: CollaborationListRepository,
    private val inventory: CollaborationSdkInventoryRepository,
    private val possession: CollaborationSdkPossessionRepository,
    private val wakes: CollaborationRecoveryWakeRepository,
    private val schedules: SyncScheduleRepository,
) {
    private val activationExecution = Mutex()
    private val commandExecution = Mutex()
    private val installationExecution = Mutex()
    private val activeRun = MutableStateFlow<ActivationRun?>(null)

    /** Automatic entry never issues a provisioning retry; explicit continuation may issue one. */
    operator fun invoke(
        workspaceId: String,
        mode: CollaborationActivationMode,
    ): Flow<AppResult<CollaborationActivationProgress>> = flow {
        val captured = capture(workspaceId)
        when (captured) {
            is AppResult.Failed -> emit(captured)
            is AppResult.Success -> emitAll(execute(workspaceId, mode, captured.value))
        }
    }

    /** Application entry is fenced to its observed owner, including after waiting for execution. */
    operator fun invoke(
        owner: CollaborationOwner
    ): Flow<AppResult<CollaborationActivationProgress>> = flow {
        if (!runtimes.supportsDeviceActivation) {
            emit(
                collaborationSetupFailure(
                    "client.collaboration_runtime_unavailable",
                    FailureKind.Unavailable,
                )
            )
            return@flow
        }
        val selected = workspaces.current(owner.sessionId)
        currentCoroutineContext().ensureActive()
        if (selected is AppResult.Failed) {
            emit(selected)
            return@flow
        }
        val workspace = (selected as AppResult.Success).value
        if (workspace == null) {
            emit(collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized))
            return@flow
        }
        emitAll(execute(workspace.id, CollaborationActivationMode.AUTOMATIC, owner))
    }

    /** Observers share business progress; leaving a page does not cancel an application run. */
    private fun execute(
        workspaceId: String,
        mode: CollaborationActivationMode,
        owner: CollaborationOwner,
    ): Flow<AppResult<CollaborationActivationProgress>> = flow {
        val running = activeRun.value
        if (
            running?.owner == owner &&
                !running.state.value.finished &&
                running.mode != CollaborationActivationMode.INSPECT &&
                (mode != CollaborationActivationMode.AUTOMATIC || running.mode == mode)
        ) {
            running.state
                .takeWhile { observed ->
                    val result = observed.result
                    if (result != null) {
                        val admitted = continuation(workspaceId, owner, result)
                        if (admitted is AppResult.Failed) {
                            emit(admitted)
                            return@takeWhile false
                        }
                        emit(result)
                    }
                    !observed.finished
                }
                .collect()
            return@flow
        }
        activationExecution.withLock {
            val run = ActivationRun(owner, mode)
            activeRun.value = run
            try {
                val result =
                    activate(workspaceId, mode, owner) {
                        val observed = AppResult.Success(it)
                        run.state.value = ActivationRunState(observed, false)
                        emit(observed)
                    }
                run.state.value = ActivationRunState(result, true)
                emit(result)
            } finally {
                run.state.update { if (it.finished) it else ActivationRunState(null, true) }
                activeRun.compareAndSet(run, null)
            }
        }
    }

    private class ActivationRun(
        val owner: CollaborationOwner,
        val mode: CollaborationActivationMode,
    ) {
        val state = MutableStateFlow(ActivationRunState(null, false))
    }

    private data class ActivationRunState(
        val result: AppResult<CollaborationActivationProgress>?,
        val finished: Boolean,
    )

    private suspend fun activate(
        workspaceId: String,
        mode: CollaborationActivationMode,
        expectedOwner: CollaborationOwner? = null,
        progress: suspend (CollaborationActivationProgress) -> Unit,
    ): AppResult<CollaborationActivationProgress> {
        currentCoroutineContext().ensureActive()
        if (!runtimes.supportsDeviceActivation)
            return collaborationSetupFailure(
                "client.collaboration_runtime_unavailable",
                FailureKind.Unavailable,
            )
        val captured = capture(workspaceId)
        if (captured is AppResult.Failed) return captured
        val owner = (captured as AppResult.Success).value
        if (expectedOwner != null && expectedOwner != owner)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        var readback = readDevice(workspaceId, owner)
        if (readback is AppResult.Failed) return readback
        var device = (readback as AppResult.Success).value
        var statusChecks = 0
        var retried = false
        var mayCreate =
            mode == CollaborationActivationMode.CONTINUE ||
                mode == CollaborationActivationMode.AUTOMATIC
        // Bound transitions as well as readback. Failed attempts return with their original
        // retained.
        repeat(16) {
            val stored = commands.current(owner)
            val admitted = continuation(workspaceId, owner, stored)
            if (admitted is AppResult.Failed) return admitted
            if (stored is AppResult.Failed) return stored
            val record = (stored as AppResult.Success).value
            if (device?.state == CollaborationDeviceState.REVOKED)
                return AppResult.Success(
                    CollaborationActivationProgress(
                        owner,
                        device,
                        CollaborationActivationStep.REVOKED,
                    )
                )
            val advanced =
                if (record != null) {
                    if (record.command.action is CollaborationSetupAction.Retry) retried = true
                    if (mode != CollaborationActivationMode.INSPECT) mayCreate = true
                    resumeRetained(workspaceId, owner, device, record, mode, progress)
                } else {
                    val allowRetry = mode == CollaborationActivationMode.CONTINUE && !retried
                    val result =
                        advanceDevice(workspaceId, owner, device, mayCreate, allowRetry, progress)
                    if (
                        result is AppResult.Success &&
                            result.value == ActivationAdvance.CommandPrepared &&
                            device?.state == CollaborationDeviceState.PROVISIONING
                    )
                        retried = true
                    result
                }
            if (advanced is AppResult.Failed) return advanced
            when (val next = (advanced as AppResult.Success).value) {
                is ActivationAdvance.Finished -> return AppResult.Success(next.progress)
                is ActivationAdvance.AwaitReadback -> {
                    if (statusChecks >= 6) return AppResult.Success(next.progress)
                    delay((2_000L shl statusChecks++).coerceAtMost(30_000))
                }
                ActivationAdvance.CommandPrepared -> return@repeat
                ActivationAdvance.Readback -> Unit
            }
            readback = readDevice(workspaceId, owner)
            if (readback is AppResult.Failed) return readback
            device = (readback as AppResult.Success).value
        }
        return collaborationSetupFailure("client.collaboration_setup_pending")
    }

    private suspend fun resumeRetained(
        workspaceId: String,
        owner: CollaborationOwner,
        device: CollaborationDeviceSetup?,
        original: CollaborationSetupCommandRecord,
        mode: CollaborationActivationMode,
        progress: suspend (CollaborationActivationProgress) -> Unit,
    ): AppResult<ActivationAdvance> {
        val retained =
            CollaborationActivationProgress(
                owner,
                device,
                CollaborationActivationStep.RESUME,
                original.command.action,
            )
        progress(retained)
        if (mode == CollaborationActivationMode.INSPECT)
            return AppResult.Success(ActivationAdvance.Finished(retained))
        val action = original.command.action
        if (action is CollaborationSetupAction.Bootstrap) {
            if (
                device == null ||
                    device.state != CollaborationDeviceState.ACTIVE ||
                    device.id != action.deviceId
            )
                return collaborationSetupFailure("client.collaboration_setup_changed")
            val storage = inspectStore(workspaceId, owner, device, original)
            if (storage is AppResult.Failed) return storage
            when ((storage as AppResult.Success).value) {
                CollaborationActivationStore.RECOVERY_REQUIRED ->
                    return AppResult.Success(
                        ActivationAdvance.Finished(
                            retained.copy(step = CollaborationActivationStep.RECOVERY_REQUIRED)
                        )
                    )
                CollaborationActivationStore.TRANSFER_PENDING ->
                    return collaborationSetupFailure("client.collaboration_handover_pending")
                else -> Unit
            }
        }
        val record =
            if (
                original.outcome == CollaborationSetupOutcome.Pending ||
                    original.outcome is CollaborationSetupOutcome.Unconfirmed
            ) {
                val delivered = replayCommand(workspaceId, original.command.id)
                if (delivered is AppResult.Failed) return delivered
                (delivered as AppResult.Success).value
            } else original
        return when (record.outcome) {
            is CollaborationSetupOutcome.Bootstrapped -> {
                val installed = installRuntime(workspaceId, record.command.id)
                if (installed is AppResult.Failed) return installed
                when (val published = ready(workspaceId, owner, requireNotNull(device), progress)) {
                    is AppResult.Failed -> published
                    is AppResult.Success ->
                        AppResult.Success(ActivationAdvance.Finished(published.value))
                }
            }
            is CollaborationSetupOutcome.Registered,
            is CollaborationSetupOutcome.Retried -> {
                val retired = reconcileReceipt(workspaceId, record.command.id)
                if (retired is AppResult.Failed) return retired
                AppResult.Success(
                    if ((retired as AppResult.Success).value) ActivationAdvance.Readback
                    else ActivationAdvance.AwaitReadback(retained)
                )
            }
            else -> collaborationSetupFailure("client.collaboration_setup_pending")
        }
    }

    private suspend fun advanceDevice(
        workspaceId: String,
        owner: CollaborationOwner,
        device: CollaborationDeviceSetup?,
        mayCreate: Boolean,
        allowRetry: Boolean,
        progress: suspend (CollaborationActivationProgress) -> Unit,
    ): AppResult<ActivationAdvance> {
        if (device?.state == CollaborationDeviceState.ACTIVE)
            return activateRuntime(workspaceId, owner, device, mayCreate, progress)
        val step =
            when (device?.state) {
                null -> CollaborationActivationStep.REGISTER
                CollaborationDeviceState.PROVISIONING ->
                    if (
                        device.provisioning?.state in
                            setOf(
                                CollaborationProvisioningState.FAILED,
                                CollaborationProvisioningState.CANCELLED,
                            )
                    )
                        CollaborationActivationStep.RETRY_PROVISIONING
                    else CollaborationActivationStep.WAITING
                else -> CollaborationActivationStep.REVOKED
            }
        val observed = CollaborationActivationProgress(owner, device, step)
        progress(observed)
        val action =
            when (step) {
                CollaborationActivationStep.REGISTER -> {
                    if (!mayCreate) return AppResult.Success(ActivationAdvance.Finished(observed))
                    CollaborationSetupAction.Register("HRIS ${build.platform.name}")
                }
                CollaborationActivationStep.RETRY_PROVISIONING -> {
                    requireNotNull(device)
                    val provisioning = requireNotNull(device.provisioning)
                    if (
                        !allowRetry ||
                            (provisioning.retryNotBeforeEpochMillis ?: Long.MIN_VALUE) >
                                clock.now().toEpochMilliseconds()
                    )
                        return AppResult.Success(ActivationAdvance.Finished(observed))
                    CollaborationSetupAction.Retry(device.id, device.version, provisioning.version)
                }
                CollaborationActivationStep.WAITING ->
                    return AppResult.Success(ActivationAdvance.AwaitReadback(observed))
                else -> return AppResult.Success(ActivationAdvance.Finished(observed))
            }
        return when (val prepared = prepareCommand(workspaceId, action)) {
            is AppResult.Failed -> prepared
            is AppResult.Success -> AppResult.Success(ActivationAdvance.CommandPrepared)
        }
    }

    private suspend fun activateRuntime(
        workspaceId: String,
        owner: CollaborationOwner,
        device: CollaborationDeviceSetup,
        mayCreate: Boolean,
        progress: suspend (CollaborationActivationProgress) -> Unit,
    ): AppResult<ActivationAdvance> {
        val observed =
            CollaborationActivationProgress(owner, device, CollaborationActivationStep.BOOTSTRAP)
        progress(observed)
        val opened = runtimes.isOpen(owner, device.id)
        val checked = continuation(workspaceId, owner, opened)
        if (checked is AppResult.Failed) return checked
        if (opened is AppResult.Failed) return opened
        if (!(opened as AppResult.Success).value) {
            val storage = inspectStore(workspaceId, owner, device, null)
            if (storage is AppResult.Failed) return storage
            when ((storage as AppResult.Success).value) {
                CollaborationActivationStore.RECOVERY_REQUIRED ->
                    return AppResult.Success(
                        ActivationAdvance.Finished(
                            observed.copy(step = CollaborationActivationStep.RECOVERY_REQUIRED)
                        )
                    )
                CollaborationActivationStore.TRANSFER_PENDING ->
                    return collaborationSetupFailure("client.collaboration_handover_pending")
                CollaborationActivationStore.ORIGINAL_ACCOUNT -> {
                    val restored = restoreRuntime(workspaceId, owner, device.id)
                    if (
                        restored is AppResult.Failed &&
                            (!mayCreate ||
                                restored.failure.kind != FailureKind.Unauthorized ||
                                restored.failure.code != "client.collaboration_sdk_unauthorized")
                    )
                        return restored
                    if (restored is AppResult.Success && restored.value) {
                        return when (val published = ready(workspaceId, owner, device, progress)) {
                            is AppResult.Failed -> published
                            is AppResult.Success ->
                                AppResult.Success(ActivationAdvance.Finished(published.value))
                        }
                    }
                }
                CollaborationActivationStore.RUNTIME_OPEN -> {
                    return when (val published = ready(workspaceId, owner, device, progress)) {
                        is AppResult.Failed -> published
                        is AppResult.Success ->
                            AppResult.Success(ActivationAdvance.Finished(published.value))
                    }
                }
                CollaborationActivationStore.FIRST_INITIALIZATION -> Unit
            }
            if (!mayCreate) return AppResult.Success(ActivationAdvance.Finished(observed))
            return when (
                val prepared =
                    prepareCommand(
                        workspaceId,
                        CollaborationSetupAction.Bootstrap(
                            device.id,
                            device.version,
                            device.gateway?.version,
                        ),
                    )
            ) {
                is AppResult.Failed -> prepared
                is AppResult.Success -> AppResult.Success(ActivationAdvance.CommandPrepared)
            }
        }
        return when (val published = ready(workspaceId, owner, device, progress)) {
            is AppResult.Failed -> published
            is AppResult.Success -> AppResult.Success(ActivationAdvance.Finished(published.value))
        }
    }

    private sealed interface ActivationAdvance {
        data class Finished(val progress: CollaborationActivationProgress) : ActivationAdvance

        data class AwaitReadback(val progress: CollaborationActivationProgress) : ActivationAdvance

        data object CommandPrepared : ActivationAdvance

        data object Readback : ActivationAdvance
    }

    private suspend fun inspectStore(
        workspaceId: String,
        owner: CollaborationOwner,
        device: CollaborationDeviceSetup,
        record: CollaborationSetupCommandRecord?,
    ): AppResult<CollaborationActivationStore> {
        val reserved = runtimes.reserveInspection(owner, device.id)
        // Own a late reservation before the first suspending authority check.
        val lease =
            (reserved as? AppResult.Success)?.value as? CollaborationRuntimeInspection.Reserved
        try {
            val granted = continuation(workspaceId, owner, reserved)
            if (granted is AppResult.Failed) return granted
            if (reserved is AppResult.Failed) return reserved
            if ((reserved as AppResult.Success).value == CollaborationRuntimeInspection.Open)
                return AppResult.Success(CollaborationActivationStore.RUNTIME_OPEN)
            requireNotNull(lease)
            if (lease.owner != owner || lease.deviceId != device.id)
                return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
            if (!lease.isCurrent())
                return collaborationSetupFailure("client.collaboration_runtime_not_retained")
            val inspected = inventory.inspect(owner, device.id)
            val admitted = continuation(workspaceId, owner, inspected)
            if (admitted is AppResult.Failed) return admitted
            if (inspected is AppResult.Failed) return inspected
            if (!lease.isCurrent())
                return collaborationSetupFailure("client.collaboration_runtime_not_retained")
            val facts = (inspected as AppResult.Success).value
            val decision = collaborationActivationStore(owner, device, record, facts)
            if (decision != CollaborationActivationStore.ORIGINAL_ACCOUNT)
                return AppResult.Success(decision)
            val possessed = possession.readPrivateIdentity(owner, device.id)
            val current = continuation(workspaceId, owner, possessed)
            if (current is AppResult.Failed) return current
            if (possessed is AppResult.Failed) return possessed
            if (!lease.isCurrent())
                return collaborationSetupFailure("client.collaboration_runtime_not_retained")
            return AppResult.Success(
                if (
                    collaborationActivationPossessionMatches(
                        facts,
                        (possessed as AppResult.Success).value,
                    )
                )
                    CollaborationActivationStore.ORIGINAL_ACCOUNT
                else CollaborationActivationStore.RECOVERY_REQUIRED
            )
        } finally {
            lease?.close()
        }
    }

    private suspend fun ready(
        workspaceId: String,
        owner: CollaborationOwner,
        device: CollaborationDeviceSetup,
        progress: suspend (CollaborationActivationProgress) -> Unit,
    ): AppResult<CollaborationActivationProgress> {
        val admitted = continuation(workspaceId, owner, null)
        if (admitted is AppResult.Failed) return admitted
        val wake = wakes.request()
        val retained = continuation(workspaceId, owner, wake)
        if (retained is AppResult.Failed) return retained
        if (wake is AppResult.Failed) return wake
        val ready =
            CollaborationActivationProgress(owner, device, CollaborationActivationStep.READY)
        progress(ready)
        currentCoroutineContext().ensureActive()
        val scheduled =
            withTimeoutOrNull(1_500) { schedules.request(CollaborationRecoveryWake.WORK_KEY) }
                ?: AppResult.Failed(
                    Failure(
                        FailureKind.Timeout,
                        "Could not request chat recovery.",
                        code = "client.collaboration_recovery_schedule_timeout",
                    )
                )
        currentCoroutineContext().ensureActive()
        val current = continuation(workspaceId, owner, scheduled)
        if (current is AppResult.Failed) return current
        // Local publication is complete. The retained wake survives an unavailable OS scheduler.
        return AppResult.Success(
            ready.copy(recoveryFailure = (scheduled as? AppResult.Failed)?.failure)
        )
    }

    private suspend fun restoreRuntime(
        workspaceId: String,
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<Boolean> {
        var lease: CollaborationRuntimeLease? = null
        var published = false
        try {
            val restored = runtimes.restore(owner, deviceId)
            if (restored is AppResult.Success) lease = restored.value
            val admitted = continuation(workspaceId, owner, restored)
            if (admitted is AppResult.Failed)
                return AppResult.Failed(
                    retainCollaborationCommandFailure(
                        lease?.credentialFailures?.value,
                        admitted.failure,
                    )
                )
            if (restored is AppResult.Failed) return restored
            val runtime = lease ?: return AppResult.Success(false)
            if (runtime.owner != owner || runtime.deviceId != deviceId)
                return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
            runtime.credentialFailures.value?.let {
                return AppResult.Failed(it)
            }
            currentCoroutineContext().ensureActive()
            val publication = runtime.publish()
            if (publication is AppResult.Failed) return publication
            published = true
            return AppResult.Success(true)
        } finally {
            if (!published) withContext(NonCancellable) { lease?.close() }
        }
    }

    /**
     * Device acquisition may reopen a retained denial only through the replica's revision fence.
     */
    private suspend fun readDevice(
        workspaceId: String,
        owner: CollaborationOwner,
    ): AppResult<CollaborationDeviceSetup?> {
        val access = owner.localListAccess()
        val initial = replicas.admission(access)
        if (initial is AppResult.Failed) return initial
        val gate = (initial as AppResult.Success).value
        var denial = gate.denial
        fun failed(failure: Failure) =
            AppResult.Failed(
                if (denial != null && !collaborationListResponseClosesRead(failure))
                    requireNotNull(denial)
                else failure
            )
        val before = continuation(workspaceId, owner, null)
        if (before is AppResult.Failed) return failed(before.failure)
        val read = setup.read(owner)
        if (read is AppResult.Failed && collaborationListResponseClosesRead(read.failure)) {
            denial = read.failure
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) { replicas.restrict(access, read.failure) }
            }
        }
        val admitted = continuation(workspaceId, owner, read)
        if (admitted is AppResult.Failed) return failed(admitted.failure)
        if (read is AppResult.Failed) return failed(read.failure)
        val device = (read as AppResult.Success).value
        if (device != null && device.platform.name != build.platform.name.uppercase())
            return failed(
                collaborationSetupFailure("client.collaboration_platform_changed").failure
            )
        val reopened = replicas.admit(access, gate.revision)
        val latest = continuation(workspaceId, owner, reopened)
        if (latest is AppResult.Failed) return failed(latest.failure)
        if (reopened is AppResult.Failed) return failed(reopened.failure)
        if (!(reopened as AppResult.Success).value) {
            val changed = replicas.admission(access)
            val checked = continuation(workspaceId, owner, changed)
            if (checked is AppResult.Failed) return failed(checked.failure)
            return when (changed) {
                is AppResult.Failed -> failed(changed.failure)
                is AppResult.Success ->
                    changed.value.denial?.let { AppResult.Failed(it) }
                        ?: failed(collaborationListConflict().failure)
            }
        }
        return read
    }

    internal suspend fun prepareCommand(
        workspaceId: String,
        action: CollaborationSetupAction,
    ): AppResult<CollaborationSetupCommandRecord> {
        val owner =
            when (val captured = capture(workspaceId)) {
                is AppResult.Failed -> return captured
                is AppResult.Success -> captured.value
            }
        val stored = commands.current(owner)
        val admitted = continuation(workspaceId, owner, stored)
        if (admitted is AppResult.Failed) return admitted
        val existing =
            when (stored) {
                is AppResult.Failed -> return stored
                is AppResult.Success -> stored.value
            }
        if (existing != null) return retained(existing, action, owner)
        val device = setup.read(owner)
        val refreshed = continuation(workspaceId, owner, device)
        if (refreshed is AppResult.Failed) return refreshed
        if (device is AppResult.Failed) return device
        val platform =
            if (build.platform == AppPlatform.Android) CollaborationNativePlatform.ANDROID
            else CollaborationNativePlatform.IOS
        val valid =
            validateCollaborationSetupAction(
                action,
                (device as AppResult.Success).value,
                platform,
                clock.now(),
            )
        if (valid is AppResult.Failed) return valid
        val command =
            CollaborationSetupCommand(
                Uuid.random().toString(),
                owner.setupBinding(),
                platform,
                build.number,
                clock.now(),
                action,
            )
        currentCoroutineContext().ensureActive()
        val inserted = commands.insert(owner, command)
        val insertedOwner = continuation(workspaceId, owner, inserted)
        if (insertedOwner is AppResult.Failed) return insertedOwner
        when (inserted) {
            is AppResult.Failed -> return inserted
            is AppResult.Success ->
                when (inserted.value) {
                    CollaborationSetupInsertion.STORED,
                    CollaborationSetupInsertion.OCCUPIED -> Unit
                    CollaborationSetupInsertion.CAPACITY_EXCEEDED ->
                        return collaborationSetupFailure(
                            "client.command_queue_full",
                            FailureKind.Storage,
                        )
                    CollaborationSetupInsertion.SCOPE_CHANGED ->
                        return collaborationSetupFailure(
                            "client.session_changed",
                            FailureKind.Unauthorized,
                        )
                }
        }
        val saved = commands.current(owner)
        val current = continuation(workspaceId, owner, saved)
        if (current is AppResult.Failed) return current
        return when (saved) {
            is AppResult.Failed -> saved
            is AppResult.Success ->
                saved.value?.let { retained(it, action, owner) }
                    ?: collaborationSetupFailure("client.collaboration_command_changed")
        }
    }

    private fun retained(
        record: CollaborationSetupCommandRecord,
        action: CollaborationSetupAction,
        owner: CollaborationOwner,
    ): AppResult<CollaborationSetupCommandRecord> =
        if (
            record.command.binding == owner.setupBinding() &&
                record.command.action == action &&
                record.command.clientBuild == build.number &&
                record.command.platform.name == build.platform.name.uppercase()
        )
            AppResult.Success(record)
        else collaborationSetupFailure("client.collaboration_setup_pending")

    internal suspend fun replayCommand(
        workspaceId: String,
        commandId: String,
    ): AppResult<CollaborationSetupCommandRecord> =
        commandExecution.withLock {
            val owner =
                when (val captured = capture(workspaceId)) {
                    is AppResult.Failed -> return@withLock captured
                    is AppResult.Success -> captured.value
                }
            val stored = commands.current(owner)
            val admitted = continuation(workspaceId, owner, stored)
            if (admitted is AppResult.Failed) return@withLock admitted
            val record =
                when (stored) {
                    is AppResult.Failed -> return@withLock stored
                    is AppResult.Success ->
                        stored.value
                            ?: return@withLock collaborationSetupFailure(
                                "client.collaboration_command_changed"
                            )
                }
            if (record.command.id != commandId || record.command.binding != owner.setupBinding())
                return@withLock collaborationSetupFailure("client.collaboration_command_changed")
            if (
                record.command.clientBuild != build.number ||
                    record.command.platform.name != build.platform.name.uppercase()
            )
                return@withLock collaborationSetupFailure("client.collaboration_build_changed")
            if (
                record.outcome != CollaborationSetupOutcome.Pending &&
                    record.outcome !is CollaborationSetupOutcome.Unconfirmed
            )
                return@withLock AppResult.Success(record)
            if (record.attempts >= 1_000)
                return@withLock collaborationSetupFailure("client.collaboration_attempt_limit")
            deliver(workspaceId, owner, record)
        }

    private suspend fun deliver(
        workspaceId: String,
        owner: CollaborationOwner,
        original: CollaborationSetupCommandRecord,
    ): AppResult<CollaborationSetupCommandRecord> {
        val uncertain =
            CollaborationSetupOutcome.Unconfirmed(
                (original.outcome as? CollaborationSetupOutcome.Unconfirmed)?.failure
            )
        val sending =
            original.copy(
                revision = original.revision + 1,
                attempts = original.attempts + 1,
                outcome = uncertain,
            )
        val previousFailure = (original.outcome as? CollaborationSetupOutcome.Unconfirmed)?.failure
        val preparing =
            when (val saved = commands.update(owner, original, uncertain, sending.attempts)) {
                is AppResult.Failed ->
                    AppResult.Failed(
                        retainCollaborationCommandFailure(previousFailure, saved.failure)
                    )
                is AppResult.Success -> saved
            }
        val preparedOwner =
            continuation(
                workspaceId,
                owner,
                if (preparing is AppResult.Failed) preparing
                else previousFailure?.let { AppResult.Failed(it) },
            )
        if (preparedOwner is AppResult.Failed) return preparedOwner
        when (val saved = preparing) {
            is AppResult.Failed -> return saved
            is AppResult.Success ->
                if (!saved.value)
                    return collaborationSetupFailure("client.collaboration_command_changed")
        }
        val admitted =
            continuation(workspaceId, owner, previousFailure?.let { AppResult.Failed(it) })
        if (admitted is AppResult.Failed) return admitted
        val result =
            when (val delivered = send(owner, original.command)) {
                is AppResult.Failed ->
                    AppResult.Failed(
                        retainCollaborationCommandFailure(
                            (original.outcome as? CollaborationSetupOutcome.Unconfirmed)?.failure,
                            delivered.failure,
                        )
                    )
                is AppResult.Success -> delivered
            }
        val current = continuation(workspaceId, owner, result)
        if (current is AppResult.Failed) return current
        val outcome =
            when (result) {
                is AppResult.Failed -> CollaborationSetupOutcome.Unconfirmed(result.failure)
                is AppResult.Success -> result.value
            }
        if (
            outcome is CollaborationSetupOutcome.Bootstrapped &&
                !collaborationGatewayMatchesCommand(owner, original.command, outcome.gateway)
        )
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        val saved = commands.update(owner, sending, outcome, sending.attempts)
        val completion =
            if (saved is AppResult.Failed)
                AppResult.Failed(
                    retainCollaborationCommandFailure(
                        (result as? AppResult.Failed)?.failure,
                        saved.failure,
                    )
                )
            else result
        val latest = continuation(workspaceId, owner, completion)
        if (latest is AppResult.Failed) return latest
        // Confirmed access failures remain authoritative even when outcome persistence fails.
        if (completion is AppResult.Failed) return completion
        return when (saved) {
            is AppResult.Failed -> saved
            is AppResult.Success ->
                if (saved.value)
                    AppResult.Success(
                        sending.copy(revision = sending.revision + 1, outcome = outcome)
                    )
                else collaborationSetupFailure("client.collaboration_command_changed")
        }
    }

    private suspend fun send(
        owner: CollaborationOwner,
        command: CollaborationSetupCommand,
    ): AppResult<CollaborationSetupOutcome> =
        when (val action = command.action) {
            is CollaborationSetupAction.Register ->
                when (
                    val result =
                        setup.register(
                            owner,
                            CollaborationRegistration(command.id, command.platform, action.label),
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(CollaborationSetupOutcome.Registered(result.value))
                }
            is CollaborationSetupAction.Retry ->
                when (
                    val result =
                        setup.retry(
                            owner,
                            CollaborationProvisioningRetry(
                                command.id,
                                action.deviceId,
                                action.deviceVersion,
                                action.provisioningVersion,
                            ),
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(CollaborationSetupOutcome.Retried(result.value))
                }
            is CollaborationSetupAction.Bootstrap ->
                when (
                    val result =
                        setup.bootstrap(
                            owner,
                            CollaborationBootstrap(
                                command.id,
                                action.deviceId,
                                action.deviceVersion,
                                action.gatewayVersion,
                            ),
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(CollaborationSetupOutcome.Bootstrapped(result.value))
                }
        }

    internal suspend fun reconcileReceipt(
        workspaceId: String,
        commandId: String,
    ): AppResult<Boolean> {
        val owner =
            when (val captured = capture(workspaceId)) {
                is AppResult.Failed -> return captured
                is AppResult.Success -> captured.value
            }
        val stored = commands.current(owner)
        val admitted = continuation(workspaceId, owner, stored)
        if (admitted is AppResult.Failed) return admitted
        val record =
            when (stored) {
                is AppResult.Failed -> return stored
                is AppResult.Success -> stored.value
            }
        if (record == null || record.command.id != commandId) return AppResult.Success(false)
        if (record.command.binding != owner.setupBinding())
            return collaborationSetupFailure("client.collaboration_command_changed")
        if (
            record.outcome !is CollaborationSetupOutcome.Registered &&
                record.outcome !is CollaborationSetupOutcome.Retried
        )
            return AppResult.Success(false)
        val device = setup.read(owner)
        val current = continuation(workspaceId, owner, device)
        if (current is AppResult.Failed) return current
        if (device is AppResult.Failed) return device
        if (!collaborationSetupReceiptObserved(record, (device as AppResult.Success).value))
            return AppResult.Success(false)
        val removed = commands.remove(owner, record)
        val latest = continuation(workspaceId, owner, removed)
        return if (latest is AppResult.Failed) latest else removed
    }

    internal suspend fun installRuntime(
        workspaceId: String,
        commandId: String,
    ): AppResult<CollaborationRuntimeLease> =
        installationExecution.withLock {
            val owner =
                when (val captured = capture(workspaceId)) {
                    is AppResult.Failed -> return@withLock captured
                    is AppResult.Success -> captured.value
                }
            val stored = commands.current(owner)
            val admitted = continuation(workspaceId, owner, stored)
            if (admitted is AppResult.Failed) return@withLock admitted
            val record =
                when (stored) {
                    is AppResult.Failed -> return@withLock stored
                    is AppResult.Success ->
                        stored.value
                            ?: return@withLock collaborationSetupFailure(
                                "client.collaboration_command_changed"
                            )
                }
            val valid = validate(owner, record, commandId)
            if (valid is AppResult.Failed) return@withLock valid
            install(workspaceId, owner, record)
        }

    private suspend fun install(
        workspaceId: String,
        owner: CollaborationOwner,
        record: CollaborationSetupCommandRecord,
    ): AppResult<CollaborationRuntimeLease> {
        var lease: CollaborationRuntimeLease? = null
        var transferred = false
        try {
            val installed = runtimes.install(owner, record)
            // Own a late successful resource before any suspending admission check.
            if (installed is AppResult.Success) lease = installed.value
            val admitted = continuation(workspaceId, owner, installed)
            if (admitted is AppResult.Failed)
                return AppResult.Failed(
                    retainCollaborationCommandFailure(
                        lease?.credentialFailures?.value,
                        admitted.failure,
                    )
                )
            if (installed is AppResult.Failed) return installed
            val runtime = checkNotNull(lease)
            val deviceId = (record.command.action as CollaborationSetupAction.Bootstrap).deviceId
            if (runtime.owner != owner || runtime.deviceId != deviceId)
                return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
            if (runtime.installedOperationId != record.command.id)
                return collaborationSetupFailure("client.collaboration_command_changed")
            runtime.credentialFailures.value?.let {
                return AppResult.Failed(it)
            }

            val retired = commands.remove(owner, record)
            val latest = continuation(workspaceId, owner, retired)
            if (latest is AppResult.Failed)
                return AppResult.Failed(
                    retainCollaborationCommandFailure(
                        runtime.credentialFailures.value,
                        latest.failure,
                    )
                )
            when (retired) {
                is AppResult.Failed -> return retired
                is AppResult.Success ->
                    if (!retired.value)
                        return collaborationSetupFailure("client.collaboration_command_changed")
            }
            // A write failure during retirement cannot publish a ready resource.
            runtime.credentialFailures.value?.let {
                return AppResult.Failed(it)
            }
            currentCoroutineContext().ensureActive()
            val published = runtime.publish()
            if (published is AppResult.Failed) return published
            transferred = true
            return AppResult.Success(runtime)
        } finally {
            if (!transferred) withContext(NonCancellable) { lease?.close() }
        }
    }

    private fun validate(
        owner: CollaborationOwner,
        record: CollaborationSetupCommandRecord,
        commandId: String,
    ): AppResult<Unit> {
        val command = record.command
        if (command.id != commandId || command.binding != owner.setupBinding())
            return collaborationSetupFailure("client.collaboration_command_changed")
        if (
            command.clientBuild != build.number ||
                command.platform.name != build.platform.name.uppercase()
        )
            return collaborationSetupFailure("client.collaboration_build_changed")
        val outcome =
            record.outcome as? CollaborationSetupOutcome.Bootstrapped
                ?: return collaborationSetupFailure("client.collaboration_setup_pending")
        if (
            record.attempts <= 0 ||
                !collaborationGatewayMatchesCommand(owner, command, outcome.gateway)
        )
            return collaborationSetupFailure("client.collaboration_command_changed")
        return AppResult.Success(Unit)
    }

    private suspend fun capture(workspaceId: String) =
        sessions.captureCollaborationOwner(workspaceId, workspaces, server, build, clock)

    private suspend fun continuation(
        workspaceId: String,
        owner: CollaborationOwner,
        received: AppResult<*>?,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        return requireCollaborationOwner(capture(workspaceId), owner, received)
    }
}
