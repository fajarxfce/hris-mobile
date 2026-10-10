package dev.fajar.starter.dashboard.data.di

import dev.fajar.starter.dashboard.domain.repositories.DashboardRepository
import dev.fajar.starter.dashboard.domain.usecases.LoadDashboard
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module

@Module
@ComponentScan("dev.fajar.starter.dashboard.data")
class DashboardDataModule {
    @Factory fun load(repository: DashboardRepository) = LoadDashboard(repository)
}
