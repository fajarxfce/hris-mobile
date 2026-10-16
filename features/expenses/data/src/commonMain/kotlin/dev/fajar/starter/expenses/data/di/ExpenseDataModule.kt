package dev.fajar.starter.expenses.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.documents.domain.repositories.DocumentOutputRepository
import dev.fajar.starter.expenses.domain.replica.repositories.ExpenseReplicaRepository
import dev.fajar.starter.expenses.domain.replica.usecases.*
import dev.fajar.starter.expenses.domain.repositories.*
import dev.fajar.starter.expenses.domain.usecases.*
import dev.fajar.starter.expenses.domain.working.repositories.ExpenseWorkingCopyRepository
import dev.fajar.starter.expenses.domain.working.usecases.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.expenses.data")
class ExpenseDataModule {
    @Single @Named("expense-acquisition") fun expenseAcquisition() = Mutex()

    @Single @Named("expense-content-key-creation") fun expenseContentKeyCreation() = Mutex()

    @Factory
    fun openExpenseWorkingCopy(
        copies: ExpenseWorkingCopyRepository,
        replicas: ExpenseReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = OpenExpenseWorkingCopy(copies, replicas, sessions, workspaces, Clock.System, build)

    @Factory
    fun observeExpenseWorkingCopy(
        copies: ExpenseWorkingCopyRepository,
        replicas: ExpenseReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveExpenseWorkingCopy(copies, replicas, sessions, workspaces, Clock.System, build)

    @Factory
    fun saveExpenseWorkingCopy(
        copies: ExpenseWorkingCopyRepository,
        replicas: ExpenseReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = SaveExpenseWorkingCopy(copies, replicas, sessions, workspaces, Clock.System, build)

    @Factory
    fun discardExpenseWorkingCopy(
        copies: ExpenseWorkingCopyRepository,
        replicas: ExpenseReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = DiscardExpenseWorkingCopy(copies, replicas, sessions, workspaces, Clock.System, build)

    @Factory
    fun exportExpenseReceipt(
        receipts: ExpenseReceiptRepository,
        outputs: DocumentOutputRepository,
        replicas: ExpenseReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ExportExpenseReceipt(receipts, outputs, replicas, sessions, workspaces, Clock.System, build)

    @Factory
    fun loadExpenseDraftReferences(
        remote: ExpensePreparationRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        replicas: ExpenseReplicaRepository,
    ) = LoadExpenseDraftReferences(remote, sessions, workspaces, Clock.System, build, replicas)

    @Factory fun prepareExpenseLine() = PrepareExpenseLine()

    @Factory
    fun prepareExpenseDraft(
        commands: ExpenseCommandRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = PrepareExpenseDraft(commands, sessions, workspaces, Clock.System, build)

    @Factory
    fun prepareExpenseAction(
        commands: ExpenseCommandRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = PrepareExpenseAction(commands, sessions, workspaces, Clock.System, build)

    @Single(binds = [ReplayExpenseCommands::class, SyncTask::class])
    fun replayExpenseCommands(
        commands: ExpenseCommandRepository,
        remote: ExpenseSubmissionRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ReplayExpenseCommands(commands, remote, sessions, workspaces, Clock.System, build)

    @Factory
    fun observeExpenseCommand(
        commands: ExpenseCommandRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        replicas: ExpenseReplicaRepository,
    ) = ObserveExpenseCommand(commands, sessions, workspaces, Clock.System, build, replicas)

    @Factory
    fun reviewExpenseCommand(
        commands: ExpenseCommandRepository,
        cache: ExpenseReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        copies: ExpenseWorkingCopyRepository,
    ) = ReviewExpenseCommand(commands, cache, sessions, workspaces, Clock.System, build, copies)

    @Factory
    fun loadExpenseDraftPreparation(
        remote: ExpenseRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        replicas: ExpenseReplicaRepository,
    ) = LoadExpenseDraftPreparation(remote, sessions, workspaces, Clock.System, build, replicas)

    @Factory
    fun loadExpenseCategories(
        remote: ExpensePreparationRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        replicas: ExpenseReplicaRepository,
    ) = LoadExpenseCategories(remote, sessions, workspaces, Clock.System, build, replicas)

    @Factory
    fun loadExpenseCostCenters(
        remote: ExpensePreparationRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        replicas: ExpenseReplicaRepository,
    ) = LoadExpenseCostCenters(remote, sessions, workspaces, Clock.System, build, replicas)

    @Factory(binds = [ObserveExpenses::class, ObserveCompanyData::class])
    fun observeExpenses(
        replicas: ExpenseReplicaRepository,
        cache: ExpenseCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveExpenses(replicas, cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun observeExpenseClaim(
        replicas: ExpenseReplicaRepository,
        cache: ExpenseCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveExpenseClaim(replicas, cache, sessions, workspaces, Clock.System, build)

    @Single(binds = [SyncExpenses::class, SyncTask::class, SyncCompanyData::class])
    fun syncExpenses(
        feed: SyncFeedRepository,
        remote: ExpenseRepository,
        cache: ExpenseReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        @Named("expense-acquisition") acquisition: Mutex,
    ) = SyncExpenses(feed, remote, cache, sessions, workspaces, Clock.System, build, acquisition)

    @Factory
    fun loadExpenseClaim(
        remote: ExpenseRepository,
        cache: ExpenseReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        @Named("expense-acquisition") acquisition: Mutex,
    ) = LoadExpenseClaim(remote, cache, sessions, workspaces, Clock.System, build, acquisition)
}
