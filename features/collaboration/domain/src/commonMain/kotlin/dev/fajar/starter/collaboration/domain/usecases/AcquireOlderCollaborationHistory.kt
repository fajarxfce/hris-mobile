package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One explicit older-message request. Metadata discovery is bounded; no background retry. */
class AcquireOlderCollaborationHistory(
    private val traversal: CollaborationHistoryTraversalRepository,
    private val history: CollaborationHistoryRepository,
    private val edits: CollaborationMessageEditRepository,
    private val lists: CollaborationListRepository,
    private val conversations: CollaborationConversationRepository,
    private val setup: CollaborationSetupRepository,
    private val runtimes: CollaborationRuntimeRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        key: CollaborationHistoryTraversal,
        position: CollaborationOlderHistoryPosition,
    ): AppResult<CollaborationOlderHistoryOutcome> =
        execution.withLock {
            if (
                !validCollaborationHistoryTraversal(key) ||
                    key.purpose != CollaborationHistoryPurpose.Browse ||
                    !validCollaborationHistoryReferenceCursor(position.after) ||
                    position.attemptedThrough < -1 ||
                    (position.generation != null &&
                        !validCollaborationMessageId(position.generation)) ||
                    (position.generation == null &&
                        (position.after != null || position.attemptedThrough >= 0))
            )
                return@withLock failed(
                    "client.collaboration_invalid_history",
                    FailureKind.Validation,
                )
            val captured =
                sessions.captureMessageAccess(
                    CollaborationLocalScope.Selected(workspaceId),
                    workspaces,
                    server,
                    build,
                    clock,
                )
            if (captured is AppResult.Failed) return@withLock captured
            val owner = BrowseOwner(workspaceId, key, (captured as AppResult.Success).value)
            discover(owner, position)
        }

    private suspend fun discover(
        owner: BrowseOwner,
        position: CollaborationOlderHistoryPosition,
    ): AppResult<CollaborationOlderHistoryOutcome> {
        var after = position.after
        var generation = position.generation
        var attemptedThrough = position.attemptedThrough
        var providerPages = 0
        var extent: CollaborationHistoryProgress? = null
        var previousSequence = -1L
        // A click may pass at most 200 retained references and five provider pages. A budget
        // boundary retains the continuation; it never claims exhaustion or starts another job.
        repeat(10) {
            val loaded = readReferences(owner, after)
            if (loaded is AppResult.Failed) return loaded
            val page = (loaded as AppResult.Success).value
            if (generation != null && generation != page.progress?.generation) {
                after = null
                generation = null
                attemptedThrough = -1
                extent = null
                previousSequence = -1
                return@repeat
            }
            if (page.events.firstOrNull()?.sequence?.let { it <= previousSequence } == true)
                return failed()
            if (extent != null && !sameCollaborationHistoryExtent(extent, page.progress))
                return AppResult.Success(CollaborationOlderHistoryOutcome.Loaded())
            val reconciled = reconcile(owner, page)
            if (reconciled is AppResult.Failed) return reconciled
            val pending = readReferences(owner, after)
            if (pending is AppResult.Failed) return pending
            val remaining = (pending as AppResult.Success).value
            if (!sameCollaborationHistoryExtent(page.progress, remaining.progress))
                return AppResult.Success(CollaborationOlderHistoryOutcome.Loaded())
            val candidates =
                remaining.events.filter {
                    it.sequence > attemptedThrough && collaborationHistoryNeedsOriginal(it)
                }
            if (candidates.isNotEmpty())
                return acquireOriginals(owner, remaining, after, candidates)
            if (remaining.after != null) {
                after = remaining.after
                previousSequence = remaining.events.last().sequence
                extent = remaining.progress
            } else if (remaining.progress?.exhausted == true) {
                return AppResult.Success(CollaborationOlderHistoryOutcome.Loaded(exhausted = true))
            } else {
                if (providerPages == 5)
                    return AppResult.Success(
                        CollaborationOlderHistoryOutcome.Loaded(
                            CollaborationOlderHistoryPosition(
                                after,
                                remaining.progress?.generation,
                                attemptedThrough,
                            )
                        )
                    )
                val acquired = acquirePage(owner, remaining.progress)
                if (acquired is AppResult.Failed) return acquired
                extent = (acquired as AppResult.Success).value
                providerPages++
            }
        }
        return AppResult.Success(
            CollaborationOlderHistoryOutcome.Loaded(
                CollaborationOlderHistoryPosition(
                    after,
                    extent?.generation ?: generation,
                    attemptedThrough,
                )
            )
        )
    }

    private suspend fun readReferences(
        owner: BrowseOwner,
        after: String?,
    ): AppResult<CollaborationHistoryReferences> {
        localAdmission(owner).let { if (it is AppResult.Failed) return it }
        val loaded = traversal.observeReferences(owner.access, owner.key, after, 20).first()
        val page =
            (loaded as? AppResult.Success)
                ?.value
                ?.takeIf { it.events.size <= 20 }
                ?.let { it.copy(events = it.events.toList()) }
        localAdmission(owner).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        if (
            page == null ||
                page.progress?.incoming != null ||
                !validCollaborationPendingHistoryReferences(page, after, 20)
        )
            return failed()
        return AppResult.Success(page)
    }

    private suspend fun reconcile(
        owner: BrowseOwner,
        page: CollaborationHistoryReferences,
    ): AppResult<Unit> {
        val resolved = mutableListOf<CollaborationHistoryReference>()
        for (reference in page.events) {
            if (!collaborationHistoryNeedsOriginal(reference)) continue
            val found =
                history.getEventFact(
                    owner.access,
                    owner.key.conversationId,
                    owner.key.roomId,
                    reference.event.eventId,
                )
            localAdmission(owner).let { if (it is AppResult.Failed) return it }
            if (found is AppResult.Failed) return found
            val fact = (found as AppResult.Success).value
            val event = reference.event
            if (fact != null) {
                if (
                    fact.eventId != event.eventId ||
                        fact.sender != event.sender ||
                        (fact.timestamp == null && !fact.redacted) ||
                        (fact.timestamp != null && fact.timestamp != event.timestamp)
                )
                    return failed()
                resolved += reference
            } else {
                val replacement =
                    edits.getReplacementFact(
                        owner.access,
                        owner.key.conversationId,
                        owner.key.roomId,
                        event.eventId,
                    )
                localAdmission(owner).let { if (it is AppResult.Failed) return it }
                if (replacement is AppResult.Failed) return replacement
                val retained = (replacement as AppResult.Success).value ?: continue
                if (
                    retained.eventId != event.eventId ||
                        retained.sender != event.sender ||
                        retained.timestamp != event.timestamp
                )
                    return failed()
                resolved += reference
            }
        }
        if (resolved.isNotEmpty()) {
            val saved =
                traversal.resolve(owner.access, owner.key, requireNotNull(page.progress), resolved)
            localAdmission(owner).let { if (it is AppResult.Failed) return it }
            if (saved is AppResult.Failed) return saved
            if (!(saved as AppResult.Success).value) return failed()
        }
        return AppResult.Success(Unit)
    }

    private suspend fun acquirePage(
        owner: BrowseOwner,
        previous: CollaborationHistoryProgress?,
    ): AppResult<CollaborationHistoryProgress> {
        val captured = liveOwner(owner)
        if (captured is AppResult.Failed) return captured
        val live = (captured as AppResult.Success).value
        admitRoom(owner, live).let { if (it is AppResult.Failed) return it }
        val received =
            runtimes.readProviderHistory(live, owner.key.deviceId, owner.key.roomId, previous?.next)
        val page =
            (received as? AppResult.Success)
                ?.value
                ?.takeIf { it.events.size <= 20 }
                ?.let { it.copy(events = it.events.toList()) }
        liveAdmission(owner, live, received).let { if (it is AppResult.Failed) return it }
        if (received is AppResult.Failed) return received
        if (
            page == null ||
                page.owner != live ||
                page.requestedPosition != previous?.next ||
                page.requestedUntilPosition != null ||
                !validCollaborationProviderPage(page, owner.access, owner.key)
        )
            return failed("client.collaboration_invalid_history_page")
        admitRoom(owner, live).let { if (it is AppResult.Failed) return it }
        val staged =
            traversal.stage(
                owner.access,
                owner.key,
                previous,
                page,
                CollaborationHistoryPageOrigin.SavedPosition,
            )
        liveAdmission(owner, live, staged).let { if (it is AppResult.Failed) return it }
        if (staged is AppResult.Failed) return staged
        if (!(staged as AppResult.Success).value)
            return failed("client.collaboration_history_progress_changed")
        val loaded = traversal.progress(owner.access, owner.key)
        localAdmission(owner).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        val progress = (loaded as AppResult.Success).value
        if (
            progress == null ||
                !validCollaborationHistoryProgress(progress) ||
                progress.incoming != null ||
                progress.next != page.next ||
                (previous != null && progress.generation != previous.generation)
        )
            return failed("client.collaboration_history_progress_changed")
        return AppResult.Success(progress)
    }

    private suspend fun acquireOriginals(
        owner: BrowseOwner,
        page: CollaborationHistoryReferences,
        after: String?,
        candidates: List<CollaborationHistoryReference>,
    ): AppResult<CollaborationOlderHistoryOutcome> {
        val anchors =
            listOf(candidates.last().event.eventId, candidates.first().event.eventId).distinct()
        for (anchor in anchors) {
            val acquired = acquireWindow(owner, anchor)
            if (acquired is AppResult.Failed) {
                if (acquired.failure.code == "client.collaboration_history_focus_capacity")
                    return AppResult.Success(
                        CollaborationOlderHistoryOutcome.ReopenRequired(anchor, acquired.failure)
                    )
                return acquired
            }
        }
        val current = readReferences(owner, after)
        if (current is AppResult.Failed) return current
        val currentPage = (current as AppResult.Success).value
        if (!sameCollaborationHistoryExtent(page.progress, currentPage.progress))
            return AppResult.Success(CollaborationOlderHistoryOutcome.Loaded())
        reconcile(owner, currentPage).let { if (it is AppResult.Failed) return it }
        val pending = readReferences(owner, after)
        if (pending is AppResult.Failed) return pending
        val remaining = (pending as AppResult.Success).value
        if (!sameCollaborationHistoryExtent(page.progress, remaining.progress))
            return AppResult.Success(CollaborationOlderHistoryOutcome.Loaded())
        val remainingIds = remaining.events.map { it.event.eventId }.toSet()
        val unresolved = candidates.any { it.event.eventId in remainingIds }
        return AppResult.Success(
            CollaborationOlderHistoryOutcome.Loaded(
                CollaborationOlderHistoryPosition(
                    page.after ?: after,
                    page.progress?.generation,
                    candidates.last().sequence,
                ),
                page.after == null && remaining.progress?.exhausted == true,
                unresolved,
            )
        )
    }

    private suspend fun acquireWindow(owner: BrowseOwner, anchor: String): AppResult<Unit> {
        val captured = liveOwner(owner)
        if (captured is AppResult.Failed) return captured
        val live = (captured as AppResult.Success).value
        admitRoom(owner, live).let { if (it is AppResult.Failed) return it }
        val received =
            runtimes.readOriginalHistory(live, owner.key.deviceId, owner.key.roomId, anchor)
        val window =
            (received as? AppResult.Success)
                ?.value
                ?.takeIf {
                    it.changes.size <= 128 &&
                        it.unresolvedEventIds.size <= 128 &&
                        it.edits.size <= 128
                }
                ?.let {
                    it.copy(
                        changes = it.changes.toList(),
                        unresolvedEventIds = it.unresolvedEventIds.toList(),
                        edits = it.edits.toList(),
                    )
                }
        liveAdmission(owner, live, received).let { if (it is AppResult.Failed) return it }
        if (received is AppResult.Failed) return received
        if (
            window == null ||
                !validCollaborationOriginalWindow(
                    window,
                    live,
                    owner.key.deviceId,
                    owner.key.roomId,
                    anchor,
                )
        )
            return failed("client.collaboration_invalid_history_window")
        admitRoom(owner, live).let { if (it is AppResult.Failed) return it }
        for (batch in window.changes.chunked(16)) {
            val merged =
                history.merge(owner.access, owner.key.conversationId, owner.key.roomId, batch)
            liveAdmission(owner, live, merged).let { if (it is AppResult.Failed) return it }
            if (merged is AppResult.Failed) return merged
            if (!(merged as AppResult.Success).value)
                return failed("client.collaboration_history_changed")
        }
        for (batch in window.edits.chunked(16)) {
            val merged =
                edits.mergeEdits(owner.access, owner.key.conversationId, owner.key.roomId, batch)
            liveAdmission(owner, live, merged).let { if (it is AppResult.Failed) return it }
            if (merged is AppResult.Failed) return merged
            if (!(merged as AppResult.Success).value)
                return failed("client.collaboration_history_changed")
        }
        return AppResult.Success(Unit)
    }

    private suspend fun admitRoom(owner: BrowseOwner, live: CollaborationOwner): AppResult<Unit> {
        val registered = setup.read(live)
        liveAdmission(owner, live, registered).let { if (it is AppResult.Failed) return it }
        if (registered is AppResult.Failed) return registered
        val device = (registered as AppResult.Success).value
        if (
            device?.id != owner.key.deviceId ||
                device.state != CollaborationDeviceState.ACTIVE ||
                device.platform.name != build.platform.name.uppercase()
        )
            return failed("client.collaboration_device_unavailable", FailureKind.Unavailable)
        val read = conversations.get(live, owner.key.conversationId)
        liveAdmission(owner, live, read).let { if (it is AppResult.Failed) return it }
        if (read is AppResult.Failed) return read
        val direct = (read as AppResult.Success).value
        if (
            direct.id != owner.key.conversationId ||
                direct.companyId != live.companyId ||
                live.accountId !in listOf(direct.firstAccountId, direct.secondAccountId)
        )
            return failed("client.collaboration_conversation_mismatch", FailureKind.AccessDenied)
        val loaded = conversations.room(live, owner.key.conversationId)
        liveAdmission(owner, live, loaded).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        val room = (loaded as AppResult.Success).value
        if (
            room.conversationId != owner.key.conversationId || room.matrixRoomId != owner.key.roomId
        )
            return failed("client.collaboration_conversation_mismatch", FailureKind.AccessDenied)
        if (room.state != CollaborationDirectRoomState.READY)
            return failed("client.collaboration_room_not_ready", FailureKind.Unavailable)
        return AppResult.Success(Unit)
    }

    private suspend fun liveOwner(owner: BrowseOwner): AppResult<CollaborationOwner> {
        localAdmission(owner).let { if (it is AppResult.Failed) return it }
        val current =
            sessions.captureCollaborationOwner(
                CollaborationOperationScope.Selected(owner.workspaceId),
                workspaces,
                server,
                build,
                clock,
            )
        currentCoroutineContext().ensureActive()
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value.localListAccess() != owner.access)
            return failed("client.session_changed", FailureKind.Unauthorized)
        return current
    }

    private suspend fun liveAdmission(
        owner: BrowseOwner,
        expected: CollaborationOwner,
        received: AppResult<*>,
    ): AppResult<Unit> {
        val rejection = (received as? AppResult.Failed)?.failure
        if (rejection != null && collaborationContentResponseClosesRead(rejection)) {
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) { lists.restrict(owner.access, rejection) }
            }
            currentCoroutineContext().ensureActive()
            return AppResult.Failed(rejection)
        }
        return requireCollaborationOwner(liveOwner(owner), expected, received)
    }

    private suspend fun localAdmission(owner: BrowseOwner): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = lists.admission(owner.access)
        val current =
            sessions.captureMessageAccess(
                CollaborationLocalScope.Selected(owner.workspaceId),
                workspaces,
                server,
                build,
                clock,
            )
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != owner.access)
            return failed("client.session_changed", FailureKind.Unauthorized)
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }

    private fun failed(
        code: String = "client.collaboration_history_unresolved",
        kind: FailureKind = FailureKind.Conflict,
    ) = collaborationSetupFailure(code, kind)

    private data class BrowseOwner(
        val workspaceId: String,
        val key: CollaborationHistoryTraversal,
        val access: CollaborationLocalAccess,
    ) {
        override fun toString() = "HistoryBrowseOwner([redacted])"
    }
}
