package dev.fajar.starter.payroll.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.documents.domain.repositories.DocumentOutputRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.payroll.domain.repositories.*
import dev.fajar.starter.payroll.domain.usecases.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.payroll.data")
class PayrollDataModule {
    @Factory
    fun observePayslips(
        cache: PayslipCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObservePayslips(cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun observePayslip(
        cache: PayslipCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObservePayslip(cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun refreshPayslips(
        remote: PayslipRepository,
        cache: PayslipCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = RefreshPayslips(remote, cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun refreshPayslip(
        remote: PayslipRepository,
        cache: PayslipCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = RefreshPayslip(remote, cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun exportPayslip(
        pdf: PayslipPdfRepository,
        outputs: DocumentOutputRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ExportPayslip(pdf, outputs, sessions, workspaces, Clock.System, build)
}
