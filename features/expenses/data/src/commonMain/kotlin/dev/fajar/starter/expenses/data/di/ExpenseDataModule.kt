package dev.fajar.starter.expenses.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.expenses.domain.repositories.*
import dev.fajar.starter.expenses.domain.usecases.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.expenses.data")
class ExpenseDataModule {
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
    ) = ObserveExpenseCommand(commands, sessions, workspaces, Clock.System, build)

    @Factory
    fun reviewExpenseCommand(
        commands: ExpenseCommandRepository,
        cache: ExpenseCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ReviewExpenseCommand(commands, cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun loadExpenseDraftPreparation(
        remote: ExpenseRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = LoadExpenseDraftPreparation(remote, sessions, workspaces, Clock.System, build)

    @Factory
    fun loadExpenseCategories(
        remote: ExpensePreparationRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = LoadExpenseCategories(remote, sessions, workspaces, Clock.System, build)

    @Factory
    fun loadExpenseCostCenters(
        remote: ExpensePreparationRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = LoadExpenseCostCenters(remote, sessions, workspaces, Clock.System, build)

    @Factory
    fun observeExpenses(
        cache: ExpenseCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveExpenses(cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun observeExpenseClaim(
        cache: ExpenseCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveExpenseClaim(cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun refreshExpenses(
        remote: ExpenseRepository,
        cache: ExpenseCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = RefreshExpenses(remote, cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun refreshExpenseClaim(
        remote: ExpenseRepository,
        cache: ExpenseCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = RefreshExpenseClaim(remote, cache, sessions, workspaces, Clock.System, build)
}
