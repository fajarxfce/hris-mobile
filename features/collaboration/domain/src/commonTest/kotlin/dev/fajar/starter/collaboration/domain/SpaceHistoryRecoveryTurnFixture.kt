package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationSpaceHistoryRecoveryTurn
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow

internal class SpaceHistoryRecoveryTurnFixture {
    val base = SpaceOriginalIncomingLoadFixture()
    val runtime = base.runtime
    val access = base.access
    val account = runtime.owner.parent.localSpaceAccountAccess()
    val generation = base.id(950)
    val recovery = CollaborationSpaceHistoryRecovery(generation)
    var snapshot: CollaborationSpaceIncomingDemandSnapshot? =
        requireNotNull(base.snapshot)
            .copy(demand = requireNotNull(base.snapshot).demand.copy(historyRecovery = recovery))
    val company =
        CollaborationAccountIncomingHistoryLocator.Company(
            base.position.companyId,
            CollaborationIncomingHistoryLocator(base.position.conversationId, base.id(951)),
        )
    var page = CollaborationAccountIncomingHistoryLocatorPage(listOf(company), "history-next")
    var root: CollaborationSpaceRoot? = CollaborationSpaceRoot(access.spaceId)
    val calls = base.calls
    val hooks = base.hooks
    val failures = base.failures
    val requests = mutableListOf<Pair<String?, Int>>()
    private val reads = mutableMapOf<String, Int>()

    private suspend fun <T> read(name: String, value: () -> T): AppResult<T> {
        val count = reads.getOrElse(name) { 0 } + 1
        reads[name] = count
        val stage = name + count
        calls += stage
        hooks[stage]?.invoke()
        return failures[stage]?.let { AppResult.Failed(it) } ?: AppResult.Success(value())
    }

    val demands =
        object : CollaborationSpaceIncomingDemandRepository {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
                assertEquals(this@SpaceHistoryRecoveryTurnFixture.access, access)
                return read("demand") { snapshot }
            }

            override suspend fun replace(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceIncomingDemandSnapshot?,
                demand: CollaborationSpaceIncomingDemand,
                wake: CollaborationSpaceIncomingWake?,
            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> =
                error("Reading must not create or acknowledge work")
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
                requests += after to limit
                return read("locators") { page }
            }
        }
    val reader =
        ReadCollaborationSpaceHistoryRecoveryTurn(
            demands,
            locators,
            roots,
            base.registrations,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            base.setup.build,
            base.sync,
            object : Clock {
                override fun now() = base.native.setup.now
            },
        )

    suspend fun load() = reader(runtime)

    fun history(value: CollaborationSpaceHistoryRecovery?) {
        val current = requireNotNull(snapshot)
        snapshot = current.copy(demand = current.demand.copy(historyRecovery = value))
    }
}
