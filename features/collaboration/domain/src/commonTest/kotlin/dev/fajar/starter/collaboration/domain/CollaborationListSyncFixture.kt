package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.SyncCollaborationList
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.time.Clock

internal fun syncId(index: Int) = "a2000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

internal fun syncSummary(index: Int) =
    CollaborationDirectSummary(
        CollaborationDirectConversation(
            syncId(index),
            syncId(9001),
            syncId(1000 + index),
            syncId(9000),
            syncId(9000),
            0,
            0,
        ),
        CollaborationParticipant(syncId(1000 + index), "Fictional colleague $index"),
    )

internal class CollaborationListSyncFixture {
    val list = LocalListFixture()
    val setup = list.setup

    init {
        replaceParent(syncId(9100))
    }

    var checkpoint: CollaborationListSyncCheckpoint? = null
    val records = linkedMapOf<String, CollaborationListStoredFact>()
    var visible = emptyList<CollaborationListStoredFact>()
    val bootstraps = mutableListOf<String?>()
    val changes = mutableListOf<String>()
    var begins = 0
    var commits = 0
    var windows = 0
    var beforeRemote: suspend () -> Unit = {}
    var beforeWindow: suspend () -> Unit = {}
    var beforeCommit: suspend () -> Unit = {}
    var afterCommitFailure: Failure? = null
    var bootstrap: (String?) -> AppResult<CollaborationSummaryBootstrap> = {
        AppResult.Success(
            CollaborationSummaryBootstrap(
                listOf(CollaborationSummaryFact(syncId(1), 0, syncSummary(1))),
                null,
                "initial-changes",
                false,
                setup.now,
            )
        )
    }
    var delta: (String) -> AppResult<CollaborationSummaryChanges> = {
        AppResult.Success(
            CollaborationSummaryChanges(emptyList(), "next-changes", false, false, setup.now)
        )
    }
    val remote =
        object : CollaborationSummarySyncRepository {
            override suspend fun bootstrap(
                owner: CollaborationOwner,
                cursor: String?,
                limit: Int,
            ): AppResult<CollaborationSummaryBootstrap> {
                check(limit == 50)
                bootstraps += cursor
                beforeRemote()
                return bootstrap(cursor)
            }

            override suspend fun changes(
                owner: CollaborationOwner,
                cursor: String,
                limit: Int,
            ): AppResult<CollaborationSummaryChanges> {
                check(limit == 50)
                changes += cursor
                beforeRemote()
                return delta(cursor)
            }
        }
    val cache =
        object : CollaborationListSyncReplicaRepository {
            override suspend fun syncWindow(
                access: CollaborationLocalAccess,
                ids: List<String>,
            ): AppResult<CollaborationListSyncWindow> {
                windows++
                beforeWindow()
                return AppResult.Success(
                    CollaborationListSyncWindow(
                        checkpoint,
                        records.values.filter { it.conversationId in ids },
                    )
                )
            }

            override suspend fun beginSync(
                access: CollaborationLocalAccess,
                expected: CollaborationListSyncCheckpoint?,
                initial: CollaborationListSyncCheckpoint,
            ): AppResult<Boolean> {
                if (checkpoint != expected) return AppResult.Success(false)
                begins++
                checkpoint = initial
                records.clear()
                return AppResult.Success(true)
            }

            override suspend fun commitSync(
                access: CollaborationLocalAccess,
                expected: CollaborationListSyncCheckpoint,
                next: CollaborationListSyncCheckpoint,
                changed: List<CollaborationSummaryFact>,
            ): AppResult<Boolean> {
                beforeCommit()
                if (checkpoint != expected) return AppResult.Success(false)
                commits++
                changed.forEach {
                    records[it.conversationId] =
                        CollaborationListStoredFact(it.conversationId, it.revision, it.summary)
                }
                checkpoint = next
                if (next.published) visible = records.values.filter { it.summary != null }
                return afterCommitFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
            }
        }
    val sync =
        SyncCollaborationList(
            remote,
            cache,
            list.replica,
            setup.sessions,
            setup.workspaces,
            IdentityServer(setup.initial.binding.audience, "Fictional Android"),
            AppBuild(AppPlatform.Android, 5, "test", null),
            object : Clock {
                override fun now() = setup.now
            },
        )

    fun replaceParent(parent: String) {
        val session = setup.session ?: setup.initial
        setup.session =
            session.copy(
                user = session.user.copy(id = syncId(9000)),
                binding = session.binding.copy(serverId = parent),
            )
        val workspace = setup.workspace ?: setup.selected
        setup.workspace =
            workspace.copy(
                company = workspace.company.copy(id = syncId(9001)),
                admission = workspace.admission!!.copy(accountId = syncId(9000), familyId = parent),
            )
    }
}
