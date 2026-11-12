package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Advances one selected account row after its finite local visit; never creates a new pass. */
class CompleteCollaborationSpaceHistoryRecoveryTurn(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val locators: CollaborationIncomingHistoryLocatorRepository,
    private val roots: CollaborationSpaceRootRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val originals: CollaborationSpaceOriginalRoomMetadataRepository,
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val companyHistory: CollaborationHistoryTraversalRepository,
    private val spaceHistory: CollaborationSpaceHistoryTraversalRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val sync: CollaborationSpaceSyncRepository,
) {
    suspend operator fun invoke(
        selected: CollaborationSpaceHistoryRecoveryTurn,
        attempt: CollaborationSpaceHistoryRecoveryAttempt?,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        val runtime = selected.runtime
        val page =
            captureCollaborationSpaceHistoryRecoveryPage(
                selected.page,
                selected.recovery.afterPartition,
            )
        if (
            !validCollaborationOriginalConversationOwner(runtime.owner) ||
                !validCollaborationSpaceConversationId(runtime.deviceId) ||
                !validCollaborationSpaceConversationId(runtime.operationId) ||
                !validCollaborationSpaceIncomingSource(selected.source) ||
                !validCollaborationSpaceHistoryRecovery(selected.recovery) ||
                selected.recovery.complete ||
                page == null ||
                (page.locators.isEmpty() != (attempt == null))
        )
            return changed(FailureKind.Validation)
        val captured =
            when (attempt) {
                is CollaborationSpaceHistoryRecoveryAttempt.Original -> {
                    val read = captureRead(attempt.read) ?: return changed(FailureKind.Validation)
                    attempt.copy(read = read)
                }
                is CollaborationSpaceHistoryRecoveryAttempt.Published -> {
                    val read = captureRead(attempt.read) ?: return changed(FailureKind.Validation)
                    attempt.copy(read = read)
                }
                else -> attempt
            }
        return withTimeoutOrNull(5_000) { Completion(selected.copy(page = page), captured).run() }
            ?: changed(FailureKind.Timeout)
    }

    private inner class Completion(
        val turn: CollaborationSpaceHistoryRecoveryTurn,
        val attempt: CollaborationSpaceHistoryRecoveryAttempt?,
    ) {
        val runtime = turn.runtime
        val access = runtime.owner.localDirectoryAccess()
        val account = runtime.owner.parent.localSpaceAccountAccess()
        val locator = turn.page.locators.singleOrNull()

        suspend fun run(): AppResult<Boolean> {
            current().let { if (it is AppResult.Failed) return it }
            coverage().let { if (it is AppResult.Failed) return it }
            val read = demands.current(access)
            val raw = (read as? AppResult.Success)?.value
            val previous = raw?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            current().let { if (it is AppResult.Failed) return it }
            if (read is AppResult.Failed) return read
            val active = previous?.demand?.historyRecovery
            if (
                previous == null ||
                    !validCollaborationMessageId(previous.generation) ||
                    !validCollaborationSpaceIncomingDemand(access, previous.demand) ||
                    active == null ||
                    !sameCollaborationSpaceHistoryRecoveryTurn(turn.recovery, active)
            )
                return changed()
            val inspected = visit()
            current().let { if (it is AppResult.Failed) return it }
            if (inspected is AppResult.Failed) return inspected
            val visit = (inspected as AppResult.Success).value
            coverage().let { if (it is AppResult.Failed) return it }
            val verified = visit()
            current().let { if (it is AppResult.Failed) return it }
            if (verified is AppResult.Failed) return verified
            if ((verified as AppResult.Success).value != visit) return changed()
            val planned =
                planCollaborationSpaceHistoryRecoveryAdvance(
                    active,
                    turn.recovery,
                    turn.page.next,
                    visit.moreHistoryPages,
                    visit.unresolved,
                )
            if (planned is AppResult.Failed) return planned
            val next = (planned as AppResult.Success).value
            val write =
                demands.replace(
                    access,
                    previous,
                    previous.demand.copy(historyRecovery = next),
                    null,
                )
            // Repository replies may contain mutable lists; capture before any further suspension.
            val written =
                when (write) {
                    is AppResult.Failed -> write
                    is AppResult.Success ->
                        AppResult.Success(
                            write.value?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
                        )
                }
            current().let { if (it is AppResult.Failed) return it }
            if (written is AppResult.Failed && written.failure.kind != FailureKind.Storage)
                return written
            val found = demands.current(access)
            val retained =
                (found as? AppResult.Success)
                    ?.value
                    ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            current().let { if (it is AppResult.Failed) return it }
            if (found is AppResult.Failed) return found
            return confirmCollaborationSpaceHistoryRecoveryWrite(
                access,
                previous,
                next,
                written,
                retained,
            )
        }

        suspend fun coverage(): AppResult<Unit> {
            val found = locators.read(account, turn.recovery.afterPartition, 1)
            val page =
                (found as? AppResult.Success)?.value?.let {
                    captureCollaborationSpaceHistoryRecoveryPage(it, turn.recovery.afterPartition)
                }
            current().let { if (it is AppResult.Failed) return it }
            if (found is AppResult.Failed) return found
            if (page != turn.page) return changed()
            return AppResult.Success(Unit)
        }

        suspend fun visit(): AppResult<CollaborationHistoryRecoveryVisit> {
            if (attempt == null)
                return AppResult.Success(CollaborationHistoryRecoveryVisit(false, false))
            if (attempt == CollaborationSpaceHistoryRecoveryAttempt.Unavailable)
                return AppResult.Success(CollaborationHistoryRecoveryVisit(false, true))
            val read: CollaborationHistoryRecoveryRead
            val receipt: CollaborationHistoryRecoveryPageCompletion?
            val loaded: AppResult<CollaborationHistoryProgress?>
            when (attempt) {
                is CollaborationSpaceHistoryRecoveryAttempt.Original -> {
                    read = attempt.read
                    receipt = attempt.completion
                    val company =
                        (locator as CollaborationAccountIncomingHistoryLocator.Company).companyId
                    val scope =
                        CollaborationLocalAccess(
                            access.sessionId,
                            access.audience,
                            access.accountId,
                            company,
                        )
                    loaded = companyHistory.progress(scope, attempt.history.traversal)
                }
                is CollaborationSpaceHistoryRecoveryAttempt.Published -> {
                    read = attempt.read
                    receipt = attempt.completion
                    loaded = spaceHistory.progress(access, attempt.history.traversal)
                }
                else -> return changed()
            }
            if (loaded is AppResult.Failed) return loaded
            val progress = (loaded as AppResult.Success).value
            if (
                progress != null &&
                    ((attempt is CollaborationSpaceHistoryRecoveryAttempt.Original) !=
                        (progress.provider == null))
            )
                return changed()
            val visit =
                planCollaborationHistoryRecoveryVisit(
                    turn.recovery.generation,
                    read,
                    receipt,
                    progress,
                ) ?: return changed()
            return AppResult.Success(visit)
        }

        suspend fun current(): AppResult<Unit> {
            admission().let { if (it is AppResult.Failed) return it }
            val loaded = registrations.current(access, runtime.owner.parent.nativeSessionId)
            admission().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            val registration = (loaded as AppResult.Success).value
            if (registration != turn.registration) return changed()
            val platform =
                CollaborationNativePlatform.entries.firstOrNull {
                    it.name == build.platform.name.uppercase()
                } ?: return changed(FailureKind.Unavailable)
            val physical =
                registeredCollaborationSpaceOriginalIncomingSdk(
                    runtime,
                    turn.source,
                    turn.registration,
                    platform,
                ) ?: return changed()
            when (val row = locator) {
                null -> if (attempt != null) return changed()
                is CollaborationAccountIncomingHistoryLocator.Company -> {
                    if (physical.binding.companyId != row.companyId) {
                        if (attempt != CollaborationSpaceHistoryRecoveryAttempt.Unavailable)
                            return changed()
                    } else {
                        val position =
                            CollaborationOriginalConversationPosition(
                                row.companyId,
                                row.history.conversationId,
                            )
                        val found = originals.getByConversation(access, position)
                        admission().let { if (it is AppResult.Failed) return it }
                        if (found is AppResult.Failed) return found
                        val value = (found as AppResult.Success).value
                        if (attempt == CollaborationSpaceHistoryRecoveryAttempt.Unavailable) {
                            if (value != null) return changed()
                        } else {
                            val original =
                                attempt as? CollaborationSpaceHistoryRecoveryAttempt.Original
                                    ?: return changed()
                            val history = original.history
                            if (
                                !validCollaborationSpaceOriginalKeyHistory(runtime, history) ||
                                    history.source != turn.source ||
                                    history.conversation.conversation.position != position ||
                                    history.traversal.deviceId != row.history.deviceId ||
                                    value == null ||
                                    !validCollaborationSpaceOriginalRoomMetadata(access, value) ||
                                    value.conversation != history.conversation.conversation ||
                                    value.room != history.conversation.room ||
                                    value.room.serverName !=
                                        turn.registration.command.provider.serverName
                            )
                                return changed()
                        }
                    }
                }
                is CollaborationAccountIncomingHistoryLocator.Space -> {
                    if (row.spaceId != access.spaceId) {
                        if (attempt != CollaborationSpaceHistoryRecoveryAttempt.Unavailable)
                            return changed()
                    } else {
                        val found = bindings.getByConversation(access, row.history.conversationId)
                        admission().let { if (it is AppResult.Failed) return it }
                        if (found is AppResult.Failed) return found
                        val value = (found as AppResult.Success).value
                        if (attempt == CollaborationSpaceHistoryRecoveryAttempt.Unavailable) {
                            if (value != null) return changed()
                        } else {
                            val published =
                                attempt as? CollaborationSpaceHistoryRecoveryAttempt.Published
                                    ?: return changed()
                            val history = published.history
                            if (
                                !validCollaborationSpaceKeyHistory(runtime, history) ||
                                    history.source != turn.source ||
                                    history.traversal.conversationId !=
                                        row.history.conversationId ||
                                    history.traversal.deviceId != row.history.deviceId ||
                                    value == null ||
                                    value != history.binding ||
                                    value.room.serverName !=
                                        turn.registration.command.provider.serverName
                            )
                                return changed()
                        }
                    }
                }
            }
            val confirmed = registrations.current(access, runtime.owner.parent.nativeSessionId)
            admission().let { if (it is AppResult.Failed) return it }
            if (confirmed is AppResult.Failed) return confirmed
            if ((confirmed as AppResult.Success).value != turn.registration) return changed()
            return AppResult.Success(Unit)
        }

        suspend fun admission(): AppResult<Unit> {
            localAdmission().let { if (it is AppResult.Failed) return it }
            val loaded = roots.current(account)
            localAdmission().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            if ((loaded as AppResult.Success).value?.spaceId != access.spaceId) return changed()
            return AppResult.Success(Unit)
        }

        suspend fun localAdmission(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val current = sessions.current()
            val gate = directory.admission(access)
            currentCoroutineContext().ensureActive()
            if (gate is AppResult.Success) {
                if (!validCollaborationSpaceDirectoryAdmission(gate.value)) return changed()
                gate.value.denial?.let {
                    return AppResult.Failed(it)
                }
            }
            if (current is AppResult.Failed) return current
            val session = (current as AppResult.Success).value
            localCollaborationSpaceAccess(access, session, server.audience).let {
                if (it is AppResult.Failed) return it
            }
            if (gate is AppResult.Failed) return gate
            if (session?.binding?.serverId != runtime.owner.parent.nativeSessionId)
                return changed(FailureKind.Unauthorized)
            if (sync.incomingSource(runtime) != turn.source) return changed(FailureKind.Unavailable)
            return AppResult.Success(Unit)
        }
    }

    private fun captureRead(
        read: CollaborationHistoryRecoveryRead
    ): CollaborationHistoryRecoveryRead? =
        when (read) {
            is CollaborationHistoryRecoveryRead.Pending -> {
                if (read.references.events.size > 16) null
                else
                    read.copy(
                        references = read.references.copy(events = read.references.events.toList())
                    )
            }
            else -> read
        }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_history_recovery_turn_changed", kind)
}
