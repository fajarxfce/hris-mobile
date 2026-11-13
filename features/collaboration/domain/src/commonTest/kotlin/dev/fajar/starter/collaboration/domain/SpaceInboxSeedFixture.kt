package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.SeedCollaborationSpaceInboxOriginal
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.Flow

internal class SpaceInboxSeedFixture {
    val original = SpaceInboxCommandFixture()
    val source = original.source
    val access = original.access
    val intent = original.queued().intent
    var retained = false
    var beforeSeed: suspend (Boolean) -> Unit = {}
    var afterSeed: suspend (Boolean) -> Unit = {}
    var seedFailure: Failure? = null
    var lostResponse: Failure? = null
    var probeConflicts = 0
    var commitConflicts = 0
    var probes = 0
    var commits = 0
    var checkpointReads = 0
    var beforeProgress: suspend () -> Unit = {}
    var progressFailure: Failure? = null
    var storedProgress: CollaborationSpaceInboxRepairProgress? = null
    val demands = mutableListOf<String>()
    val publications = mutableListOf<CollaborationSpaceInboxPublication>()
    val failure = source.base.base.storageFailure

    init {
        source.base.stored = original.queued()
        source.inboxState = CollaborationSpaceInboxConversation(null, null, null, emptyList())
        source.inboxActivities.clear()
    }

    val progress =
        object : CollaborationSpaceInboxRepairProgressRepository {
            override fun observe(
                access: CollaborationSpaceLocalAccess
            ): Flow<AppResult<CollaborationSpaceInboxRepairProgress?>> =
                error("The action does not own observation")

            override suspend fun current(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceInboxRepairProgress?> {
                assertEquals(this@SpaceInboxSeedFixture.access, access)
                checkpointReads++
                beforeProgress()
                return progressFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(storedProgress)
            }

            override suspend fun replace(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceInboxRepairProgress?,
                replacement: CollaborationSpaceInboxRepairProgress,
            ): AppResult<CollaborationSpaceInboxRepairProgress?> =
                error("Seed and demand must commit together")
        }

    val inbox =
        object : CollaborationSpaceInboxRepository by source.inbox {
            override suspend fun seed(
                access: CollaborationSpaceLocalAccess,
                publication: CollaborationSpaceInboxPublication,
                repair: CollaborationSpaceInboxRepairPublication?,
            ): AppResult<CollaborationSpaceInboxSeedResult> {
                assertEquals(this@SpaceInboxSeedFixture.access, access)
                val committing = repair != null
                if (committing) commits++ else probes++
                beforeSeed(committing)
                seedFailure?.let {
                    return AppResult.Failed(it)
                }
                if (!committing && probeConflicts > 0) {
                    probeConflicts--
                    return AppResult.Success(CollaborationSpaceInboxSeedResult.CHANGED)
                }
                if (retained) return AppResult.Success(CollaborationSpaceInboxSeedResult.RETAINED)
                if (repair == null) {
                    afterSeed(false)
                    return AppResult.Success(CollaborationSpaceInboxSeedResult.DEMAND_REQUIRED)
                }
                demands += repair.replacement.latestDemand
                if (commitConflicts > 0) {
                    commitConflicts--
                    return AppResult.Success(CollaborationSpaceInboxSeedResult.CHANGED)
                }
                if (
                    publication.expected != source.inboxState.version ||
                        repair.expected != storedProgress
                )
                    return AppResult.Success(CollaborationSpaceInboxSeedResult.CHANGED)
                assertTrue(validCollaborationSpaceInboxRepairPublication(repair))
                assertNull(publication.draft)
                assertEquals(CollaborationSpaceInboxRepairChange.Retain(intent), publication.repair)
                publications += publication
                publication.activities.forEach { source.inboxActivities[it.source] = it }
                source.inboxState =
                    source.inboxState.copy(
                        version =
                            CollaborationSpaceInboxVersion(
                                original.generation,
                                (source.inboxState.version?.revision ?: -1) + 1,
                                publication.lastOrdinal,
                            ),
                        entry = publication.entry,
                        candidates =
                            source.inboxActivities.values
                                .filter { it.preview != null }
                                .sortedWith { a, b ->
                                    compareCollaborationSpaceInboxPositions(b.position, a.position)
                                }
                                .take(2),
                    )
                storedProgress = repair.replacement
                retained = true
                afterSeed(true)
                return lostResponse?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(CollaborationSpaceInboxSeedResult.STORED)
            }
        }

    fun usecase() =
        SeedCollaborationSpaceInboxOriginal(
            source.base.messages,
            source.base.metadata,
            inbox,
            progress,
            source.base.base.directory,
            source.base.sessions,
            source.base.native.server,
        )

    suspend fun invoke() = usecase()(access, intent)

    fun at(boundary: String, action: suspend () -> Unit) {
        when (boundary) {
            "command" -> source.base.beforeRead = action
            "metadata" -> source.base.beforeCanonical = action
            "index" -> source.beforeInboxRead = action
            "anchor" -> source.beforeActivityRead = action
            "probe" -> afterSeed = { if (!it) action() }
            "progress" -> beforeProgress = action
            "commit" -> afterSeed = { if (it) action() }
            else -> error("Unknown boundary")
        }
    }
}
