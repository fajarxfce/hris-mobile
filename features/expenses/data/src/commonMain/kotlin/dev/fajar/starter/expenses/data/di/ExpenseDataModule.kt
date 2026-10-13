package dev.fajar.starter.expenses.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.expenses.domain.repositories.*
import dev.fajar.starter.expenses.domain.usecases.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.expenses.data")
class ExpenseDataModule {
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
