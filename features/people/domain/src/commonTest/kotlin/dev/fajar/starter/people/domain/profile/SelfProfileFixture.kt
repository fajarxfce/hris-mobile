package dev.fajar.starter.people.domain.profile

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.PeopleFixture
import dev.fajar.starter.people.domain.entities.*
import dev.fajar.starter.people.domain.profile.entities.*
import dev.fajar.starter.people.domain.profile.policies.*
import dev.fajar.starter.people.domain.profile.repositories.*
import dev.fajar.starter.people.domain.profile.usecases.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.LocalDate

class SelfProfileFixture {
    val base = PeopleFixture()
    val build = AppBuild(AppPlatform.Android, 1, "1", null)
    var now = Clock.System.now()
    val clock =
        object : Clock {
            override fun now() = this@SelfProfileFixture.now
        }
    val workspace = base.workspace.copy(permissions = setOf("people.self.read"))

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
        (admitSelfProfile(workspace.id, workspace, base.session, build, now) as AppResult.Success)
            .value
    val store = Records()
    val rows = (1..45).associate { selfProfileId(it) to selfProfileRecord(it) }.toMutableMap()
    val reads = mutableListOf<String>()
    var recordCall: suspend (String) -> AppResult<SelfProfileRecord> = { id ->
        rows[id]?.let { AppResult.Success(it) }
            ?: selfProfileFailure("self_profile_not_found", FailureKind.Unexpected)
    }
    val remote =
        object : SelfProfileRecordRepository {
            override suspend fun record(
                access: SelfProfileAccess,
                id: String,
            ): AppResult<SelfProfileRecord> {
                reads += id
                return recordCall(id)
            }
        }
    var bootstrapReads = 0
    var changeReads = 0
    var bootstrapCall: suspend (String?) -> AppResult<SyncBootstrapPage> = {
        AppResult.Success(
            SyncBootstrapPage(
                setOf(SyncCollection.SELF_PROFILES),
                rows.values.map { SyncReference(SyncCollection.SELF_PROFILES, it.id, it.version) },
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
                check(collections == setOf(SyncCollection.SELF_PROFILES) && limit == 50)
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
                check(collections == setOf(SyncCollection.SELF_PROFILES) && limit == 50)
                changeReads++
                return changesCall(cursor)
            }
        }
    val sync = SyncSelfProfiles(feed, remote, store, base.sessions, workspaces, clock, build)
    val observe = ObserveSelfProfiles(store, base.sessions, workspaces, clock, build)

    class Records : SelfProfileReplicaRepository {
        var checkpoint: SelfProfileCheckpoint? = null
        var records = linkedMapOf<String, SelfProfileReplicaEntry>()
        val snapshot =
            MutableStateFlow<AppResult<SelfProfileSnapshot>>(
                AppResult.Success(SelfProfileSnapshot(null, emptyList()))
            )
        val accessState =
            MutableStateFlow<AppResult<SelfProfileReadAdmission>>(
                AppResult.Success(SelfProfileReadAdmission(null, null))
            )
        var failedClear = false
        private var revision = 0

        override fun observeAdmission(access: SelfProfileAccess) = accessState

        override suspend fun admission(access: SelfProfileAccess) = accessState.value

        override suspend fun restrict(
            access: SelfProfileAccess,
            failure: Failure,
        ): AppResult<Boolean> {
            accessState.value =
                AppResult.Success(SelfProfileReadAdmission((++revision).toString(), failure))
            return AppResult.Success(true)
        }

        override suspend fun admit(
            access: SelfProfileAccess,
            expectedRevision: String?,
        ): AppResult<Boolean> {
            if ((accessState.value as AppResult.Success).value.revision != expectedRevision)
                return AppResult.Success(false)
            accessState.value = AppResult.Success(SelfProfileReadAdmission(null, null))
            return AppResult.Success(true)
        }

        override fun observe(access: SelfProfileAccess) = snapshot

        override suspend fun checkpoint(access: SelfProfileAccess) = AppResult.Success(checkpoint)

        override suspend fun begin(
            checkpoint: SelfProfileCheckpoint,
            expectedGeneration: String?,
        ): AppResult<Boolean> {
            if (this.checkpoint?.generation != expectedGeneration) return AppResult.Success(false)
            this.checkpoint = checkpoint
            records = linkedMapOf()
            return AppResult.Success(true)
        }

        override suspend fun commit(
            previous: SelfProfileCheckpoint,
            next: SelfProfileCheckpoint,
            entries: List<SelfProfileReplicaEntry>,
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
                        SelfProfileSnapshot(next, records.values.mapNotNull { it.record })
                    )
            return AppResult.Success(true)
        }

        override suspend fun clear(access: SelfProfileAccess): AppResult<Boolean> {
            if (failedClear) return AppResult.Failed(Failure(FailureKind.Storage, "Unavailable"))
            checkpoint = null
            records.clear()
            snapshot.value = AppResult.Success(SelfProfileSnapshot(null, emptyList()))
            return AppResult.Success(true)
        }
    }
}

fun selfProfileId(index: Int) = "91000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

fun selfProfileRecord(index: Int, version: Long = 0): SelfProfileRecord {
    val employee =
        Employment(
            selfProfileId(index),
            "company",
            "person",
            "account",
            "E${index.toString().padStart(5, '0')}",
            "Private employee",
            "private@example.test",
            EmploymentContract.Permanent,
            EmploymentStatus.Active,
            LocalDate(2024, 1, 1),
            null,
            4,
            2,
            LocalDate(2025, 1, 1),
        )
    val profile =
        PersonProfile(
            "person",
            "company",
            "account",
            "Private employee",
            LocalDate(1990, 2, 3),
            "ID",
            "private@example.test",
            7,
        )
    return SelfProfileRecord(
        selfProfileId(index),
        version,
        "Asia/Jakarta",
        EmploymentProfile(
            EmploymentDetails(LocalDate(2026, 10, 14), employee, null, null, null, null, null),
            profile,
        ),
    )
}
