package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow

internal class SpaceHistoryRecoveryCompletionFixture {
    val reader = SpaceKeyReferenceReadFixture()
    val base = reader.base
    val runtime = base.runtime
    val access = base.access
    val account = runtime.owner.parent.localSpaceAccountAccess()
    val generation = base.id(960)
    val recovery = CollaborationSpaceHistoryRecovery(generation)
    val company =
        CollaborationAccountIncomingHistoryLocator.Company(
            base.position.companyId,
            CollaborationIncomingHistoryLocator(
                base.position.conversationId,
                reader.original.keyHistory.traversal.deviceId,
            ),
        )
    var page = CollaborationAccountIncomingHistoryLocatorPage(listOf(company), null)
    var root: CollaborationSpaceRoot? = CollaborationSpaceRoot(access.spaceId)
    var snapshot: CollaborationSpaceIncomingDemandSnapshot? =
        CollaborationSpaceIncomingDemandSnapshot(
            base.id(4),
            CollaborationSpaceIncomingDemand(
                nextPosition = 1,
                rooms = listOf(base.captured),
                sources =
                    listOf(CollaborationSpaceIncomingSourceProgress(base.source, "position-1")),
                historyRecovery = recovery,
            ),
        )
    var turn =
        CollaborationSpaceHistoryRecoveryTurn(
            runtime,
            base.source,
            assertNotNull(base.registration),
            recovery,
            page,
        )
    var progress: CollaborationHistoryProgress? =
        assertNotNull(reader.original.progress)
            .copy(
                revision = 2,
                recovery = CollaborationHistoryRecoveryPosition(generation, 2, complete = true),
            )
    var attempt: CollaborationSpaceHistoryRecoveryAttempt? =
        CollaborationSpaceHistoryRecoveryAttempt.Original(
            reader.original.keyHistory,
            CollaborationHistoryRecoveryRead.Complete(assertNotNull(progress)),
        )
    val calls = base.calls
    val hooks = base.hooks
    val failures = base.failures
    val reads = mutableMapOf<String, Int>()
    val requests = mutableListOf<Pair<String?, Int>>()
    var writes = 0
    var commit = true
    var lostReply = false
    var fabricatedReply = false
    var response:
        ((CollaborationSpaceIncomingDemandSnapshot) -> CollaborationSpaceIncomingDemandSnapshot)? =
        null

    init {
        base.retained = CollaborationSpaceOriginalRoomMetadata(base.original, base.room)
        assertTrue(validCollaborationSpaceIncomingDemand(access, assertNotNull(snapshot).demand))
        assertTrue(validCollaborationHistoryProgress(assertNotNull(progress)))
    }

    suspend fun touch(name: String) {
        val count = reads.getOrElse(name) { 0 } + 1
        reads[name] = count
        calls += name + count
        hooks[name + count]?.invoke()
    }

    private suspend fun <T> read(name: String, value: () -> T): AppResult<T> {
        touch(name)
        return failures[name]?.let { AppResult.Failed(it) } ?: AppResult.Success(value())
    }

    val demands =
        object : CollaborationSpaceIncomingDemandRepository {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
                assertEquals(this@SpaceHistoryRecoveryCompletionFixture.access, access)
                return read("demand") { snapshot }
            }

            override suspend fun replace(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceIncomingDemandSnapshot?,
                demand: CollaborationSpaceIncomingDemand,
                wake: CollaborationSpaceIncomingWake?,
            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
                assertEquals(this@SpaceHistoryRecoveryCompletionFixture.access, access)
                assertNull(wake, "The existing incoming Finish owner retains the scheduling wake")
                writes++
                touch("write")
                if (snapshot != expected) return AppResult.Success(null)
                val next = CollaborationSpaceIncomingDemandSnapshot(base.id(970 + writes), demand)
                if (commit) snapshot = next
                touch("written")
                if (lostReply) return AppResult.Failed(base.directory.storageFailure)
                return AppResult.Success(
                    if (commit || fabricatedReply) response?.invoke(next) ?: next else null
                )
            }
        }
    val roots =
        object : CollaborationSpaceRootRepository {
            override suspend fun current(
                access: CollaborationSpaceAccountAccess
            ): AppResult<CollaborationSpaceRoot?> {
                assertEquals(account, access)
                return read("root") { root }
            }

            override fun observe(
                access: CollaborationSpaceAccountAccess
            ): Flow<AppResult<CollaborationSpaceRoot?>> = error("No collector")

            override suspend fun retain(
                access: CollaborationSpaceAccountAccess,
                root: CollaborationSpaceRoot,
            ): AppResult<Boolean> = error("No root migration")
        }
    val locators =
        object : CollaborationIncomingHistoryLocatorRepository {
            override suspend fun read(
                access: CollaborationLocalAccess,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationIncomingHistoryLocatorPage> =
                error("Do not substitute Company scope")

            override suspend fun read(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationIncomingHistoryLocatorPage> =
                error("Do not substitute current Space")

            override suspend fun read(
                access: CollaborationSpaceAccountAccess,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationAccountIncomingHistoryLocatorPage> {
                assertEquals(account, access)
                assertEquals(1, limit)
                requests += after to limit
                return read("locators") { page }
            }
        }
    val companyHistory =
        object : CollaborationHistoryTraversalRepository by reader.original.traversal {
            override suspend fun progress(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
            ): AppResult<CollaborationHistoryProgress?> {
                assertEquals(
                    CollaborationLocalAccess(
                        account.sessionId,
                        account.audience,
                        account.accountId,
                        company.companyId,
                    ),
                    access,
                )
                assertEquals(
                    (attempt as CollaborationSpaceHistoryRecoveryAttempt.Original)
                        .history
                        .traversal,
                    traversal,
                )
                return read("progress") { progress }
            }
        }
    val spaceHistory =
        object : CollaborationSpaceHistoryTraversalRepository by reader.traversal {
            override suspend fun progress(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
            ): AppResult<CollaborationHistoryProgress?> {
                assertEquals(this@SpaceHistoryRecoveryCompletionFixture.access, access)
                assertEquals(
                    (attempt as CollaborationSpaceHistoryRecoveryAttempt.Published)
                        .history
                        .traversal,
                    traversal,
                )
                return read("progress") { progress }
            }
        }
    val complete =
        CompleteCollaborationSpaceHistoryRecoveryTurn(
            demands,
            locators,
            roots,
            base.registrations,
            base.metadata,
            reader.bindings,
            companyHistory,
            spaceHistory,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            base.setup.build,
            base.sync,
        )

    var now = base.native.setup.now
    val defer =
        DeferCollaborationSpaceHistoryRecoveryTurn(
            demands,
            roots,
            base.registrations,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            base.setup.build,
            base.sync,
            object : Clock {
                override fun now() = this@SpaceHistoryRecoveryCompletionFixture.now
            },
        )

    suspend fun run() = complete(turn, attempt)

    fun retained() = assertNotNull(assertNotNull(snapshot).demand.historyRecovery)

    fun history(value: CollaborationSpaceHistoryRecovery) {
        val current = assertNotNull(snapshot)
        snapshot = current.copy(demand = current.demand.copy(historyRecovery = value))
    }

    fun published(alias: Boolean = false) {
        if (alias) reader.originalAlias()
        val history = reader.keyHistory
        page =
            page.copy(
                locators =
                    listOf(
                        CollaborationAccountIncomingHistoryLocator.Space(
                            access.spaceId,
                            CollaborationIncomingHistoryLocator(
                                history.traversal.conversationId,
                                history.traversal.deviceId,
                            ),
                        )
                    )
            )
        turn = turn.copy(page = page)
        progress = assertNotNull(progress).copy(provider = assertNotNull(reader.progress).provider)
        attempt =
            CollaborationSpaceHistoryRecoveryAttempt.Published(
                history,
                CollaborationHistoryRecoveryRead.Complete(assertNotNull(progress)),
            )
    }

    fun pending(unresolved: Int = 0, older: Boolean = false) {
        val before =
            assertNotNull(progress)
                .copy(
                    recovery =
                        CollaborationHistoryRecoveryPosition(
                            if (older) base.id(961) else generation,
                            2,
                        )
                )
        val read =
            CollaborationHistoryRecoveryRead.Pending(
                CollaborationHistoryReferences(before, List(2, reader.original::reference), null)
            )
        val receipt =
            CollaborationHistoryRecoveryPageCompletion(
                assertNotNull(before.recovery).generation,
                2 - unresolved,
                unresolved,
                null,
            )
        progress =
            before.copy(
                revision = before.revision + 1,
                recovery =
                    CollaborationHistoryRecoveryPosition(
                        generation,
                        2,
                        complete = !older,
                        unresolved = unresolved > 0 && !older,
                    ),
            )
        attempt =
            when (val value = attempt) {
                is CollaborationSpaceHistoryRecoveryAttempt.Original ->
                    value.copy(read = read, completion = receipt)
                is CollaborationSpaceHistoryRecoveryAttempt.Published ->
                    value.copy(read = read, completion = receipt)
                else -> error("A ready history is required")
            }
    }
}
