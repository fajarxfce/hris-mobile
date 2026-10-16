package dev.fajar.starter.home.presentation.datasync

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.CompanyMembership
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*

class DataSyncFixture(dataSets: List<CompanyDataSet> = CompanyDataSet.entries) {
    val now = Instant.parse("2026-10-17T00:00:00Z")
    val workspace =
        Workspace(
            "selected-workspace",
            "session",
            "https://hris.example.test/",
            CompanyMembership("company", "Example company", "EXAMPLE", "Asia/Jakarta"),
            emptySet(),
            WorkspaceAdmission("account", "family", now, now + 1.hours),
        )
    val observations = dataSets.map { StatusProbe(it, now) }
    val requests = dataSets.map { SyncProbe(it, this) }
    var active = 0
    var peak = 0

    fun observation(dataSet: CompanyDataSet) = observations.single { it.dataSet == dataSet }

    fun request(dataSet: CompanyDataSet) = requests.single { it.dataSet == dataSet }

    fun model() = DataSyncViewModel(workspace, observations, requests)
}

class StatusProbe(override val dataSet: CompanyDataSet, now: Instant) : ObserveCompanyData {
    val values =
        MutableStateFlow<AppResult<CompanyDataStatus>>(AppResult.Success(CompanyDataStatus(now)))
    var read: () -> Flow<AppResult<CompanyDataStatus>> = { values }
    val scopes = mutableListOf<String>()
    var active = 0

    override fun observeStatus(workspaceId: String): Flow<AppResult<CompanyDataStatus>> = flow {
        scopes += workspaceId
        active++
        try {
            emitAll(read())
        } finally {
            active--
        }
    }
}

class SyncProbe(override val dataSet: CompanyDataSet, private val fixture: DataSyncFixture) :
    SyncCompanyData {
    override val key = dataSet.name
    val scopes = mutableListOf<String>()
    var response = CompletableDeferred<AppResult<CompanyDataSyncOutcome>>()
    var execute: suspend () -> AppResult<CompanyDataSyncOutcome> = { response.await() }

    override suspend fun refresh(workspaceId: String): AppResult<CompanyDataSyncOutcome> {
        scopes += workspaceId
        fixture.active++
        fixture.peak = maxOf(fixture.peak, fixture.active)
        try {
            return execute()
        } finally {
            fixture.active--
        }
    }

    override suspend fun invoke(): SyncResult =
        error("The page must select a workspace explicitly.")
}

data class TestSyncOutcome(override val caughtUp: Boolean = true) : CompanyDataSyncOutcome

fun DataSyncViewModel.row(dataSet: CompanyDataSet) =
    state.value.rows.single { it.dataSet == dataSet }
