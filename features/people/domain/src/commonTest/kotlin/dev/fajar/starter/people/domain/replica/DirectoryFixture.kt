package dev.fajar.starter.people.domain.replica

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.PeopleFixture
import dev.fajar.starter.people.domain.entities.DirectoryEmployee
import dev.fajar.starter.people.domain.replica.entities.*
import dev.fajar.starter.people.domain.replica.policies.*
import dev.fajar.starter.people.domain.replica.repositories.*
import dev.fajar.starter.people.domain.replica.usecases.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.*
import kotlinx.coroutines.flow.*

class DirectoryFixture {
    val base = PeopleFixture()
    val build = AppBuild(AppPlatform.Android, 1, "1", null)
    var now = Clock.System.now()
    val clock =
        object : Clock {
            override fun now() = this@DirectoryFixture.now
        }
    val workspace = base.workspace.copy(permissions = setOf("people.directory.read"))

    init {
        base.workspaces.state.value = AppResult.Success(workspace)
    }

    var policyCall:
        suspend () -> AppResult<dev.fajar.starter.workspace.domain.entities.CompanyPolicy> =
        {
            AppResult.Success(
                workspace.policy!!
                    .policy
                    .copy(serverTime = now, validUntil = now + Duration.parse("1h"))
            )
        }
    val workspaces =
        object : WorkspaceRepository by base.workspaces {
            override suspend fun clientPolicy(sessionId: String, companyId: String) = policyCall()
        }
    val access =
        (admitDirectory(workspace.id, workspace, base.session, build, now) as AppResult.Success)
            .value
    val store = Records()
    val rows = (1..45).associate { directoryId(it) to directoryRecord(it) }.toMutableMap()
    val reads = mutableListOf<String>()
    var recordCall: suspend (String) -> AppResult<DirectoryRecord> = { id ->
        rows[id]?.let { AppResult.Success(it) }
            ?: directoryFailure("directory_employee_not_found", FailureKind.Unexpected)
    }
    val remote =
        object : DirectoryRecordRepository {
            override suspend fun record(
                access: DirectoryAccess,
                id: String,
            ): AppResult<DirectoryRecord> {
                reads += id
                return recordCall(id)
            }
        }
    var bootstrapReads = 0
    var changeReads = 0
    var bootstrapCall: suspend (String?) -> AppResult<SyncBootstrapPage> = {
        AppResult.Success(
            SyncBootstrapPage(
                setOf(SyncCollection.DIRECTORY_EMPLOYEES),
                rows.values.map {
                    SyncReference(SyncCollection.DIRECTORY_EMPLOYEES, it.employee.id, it.version)
                },
                null,
                "head",
                now,
            )
        )
    }
    var changesCall: suspend (String) -> AppResult<SyncChangePage> = {
        AppResult.Success(SyncChangePage(emptyList(), "head", false, false, 5, now))
    }
    val feed =
        object : SyncFeedRepository {
            override suspend fun bootstrap(
                sessionId: String,
                companyId: String,
                collections: Set<SyncCollection>,
                cursor: String?,
                limit: Int,
            ): AppResult<SyncBootstrapPage> {
                check(collections == setOf(SyncCollection.DIRECTORY_EMPLOYEES) && limit == 50)
                bootstrapReads++
                return bootstrapCall(cursor)
            }

            override suspend fun changes(
                sessionId: String,
                companyId: String,
                collections: Set<SyncCollection>,
                cursor: String,
                limit: Int,
            ): AppResult<SyncChangePage> {
                check(collections == setOf(SyncCollection.DIRECTORY_EMPLOYEES) && limit == 50)
                changeReads++
                return changesCall(cursor)
            }
        }
    val sync = SyncCompanyDirectory(feed, remote, store, base.sessions, workspaces, clock, build)
    val observe = ObserveCompanyDirectory(store, base.sessions, workspaces, clock, build)

    class Records : DirectoryReplicaRepository {
        var checkpoint: DirectoryCheckpoint? = null
        var records = linkedMapOf<String, DirectoryReplicaEntry>()
        val snapshot =
            MutableStateFlow<AppResult<DirectorySnapshot>>(
                AppResult.Success(DirectorySnapshot(null, emptyList()))
            )
        val accessState =
            MutableStateFlow<AppResult<DirectoryReadAdmission>>(
                AppResult.Success(DirectoryReadAdmission(null, null))
            )
        var failedClear = false
        private var revision = 0

        override fun observeAdmission(access: DirectoryAccess) = accessState

        override suspend fun admission(access: DirectoryAccess) = accessState.value

        override suspend fun restrict(
            access: DirectoryAccess,
            failure: Failure,
        ): AppResult<Boolean> {
            accessState.value =
                AppResult.Success(DirectoryReadAdmission((++revision).toString(), failure))
            return AppResult.Success(true)
        }

        override suspend fun admit(
            access: DirectoryAccess,
            expectedRevision: String?,
        ): AppResult<Boolean> {
            if ((accessState.value as AppResult.Success).value.revision != expectedRevision)
                return AppResult.Success(false)
            accessState.value = AppResult.Success(DirectoryReadAdmission(null, null))
            return AppResult.Success(true)
        }

        override fun observe(access: DirectoryAccess) = snapshot

        override suspend fun checkpoint(access: DirectoryAccess) = AppResult.Success(checkpoint)

        override suspend fun begin(
            checkpoint: DirectoryCheckpoint,
            expectedGeneration: String?,
        ): AppResult<Boolean> {
            if (this.checkpoint?.generation != expectedGeneration) return AppResult.Success(false)
            this.checkpoint = checkpoint
            records = linkedMapOf()
            return AppResult.Success(true)
        }

        override suspend fun commit(
            previous: DirectoryCheckpoint,
            next: DirectoryCheckpoint,
            entries: List<DirectoryReplicaEntry>,
        ): AppResult<Boolean> {
            if (checkpoint != previous) return AppResult.Success(false)
            for (entry in entries) {
                val old = records[entry.reference.id]
                if (
                    old == null ||
                        entry.reference.version > old.reference.version ||
                        (entry.reference.version == old.reference.version && entry.record == null)
                )
                    records[entry.reference.id] = entry
            }
            checkpoint = next
            if (next.published)
                snapshot.value =
                    AppResult.Success(
                        DirectorySnapshot(next, records.values.mapNotNull { it.record })
                    )
            return AppResult.Success(true)
        }

        override suspend fun clear(access: DirectoryAccess): AppResult<Boolean> {
            if (failedClear) return AppResult.Failed(Failure(FailureKind.Storage, "Unavailable"))
            checkpoint = null
            records.clear()
            snapshot.value = AppResult.Success(DirectorySnapshot(null, emptyList()))
            return AppResult.Success(true)
        }
    }
}

fun directoryId(index: Int) = "91000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

fun directoryRecord(index: Int, version: Long = 0) =
    DirectoryRecord(
        version,
        DirectoryEmployee(
            directoryId(index),
            "E${index.toString().padStart(5, '0')}",
            "Colleague $index",
            null,
            if (index % 2 == 0) "Engineering" else "People",
            null,
            directoryId(if (index % 2 == 0) 990 else 991),
        ),
    )
