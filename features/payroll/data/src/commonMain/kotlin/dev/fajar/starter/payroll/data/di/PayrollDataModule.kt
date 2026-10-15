package dev.fajar.starter.payroll.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.documents.domain.repositories.DocumentOutputRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.payroll.domain.replica.repositories.PayslipReplicaRepository
import dev.fajar.starter.payroll.domain.replica.usecases.SyncPayroll
import dev.fajar.starter.payroll.domain.repositories.*
import dev.fajar.starter.payroll.domain.usecases.*
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.payroll.data")
class PayrollDataModule {
    @Factory
    fun observePayslips(
        replicas: PayslipReplicaRepository,
        cache: PayslipCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObservePayslips(replicas, cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun observePayslip(
        replicas: PayslipReplicaRepository,
        cache: PayslipCacheRepository,
        revisions: PayslipRevisionRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObservePayslip(replicas, cache, revisions, sessions, workspaces, Clock.System, build)

    @Single
    fun loadPayslipRevision(
        remote: PayslipRepository,
        replicas: PayslipReplicaRepository,
        revisions: PayslipRevisionRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = LoadPayslipRevision(remote, replicas, revisions, sessions, workspaces, Clock.System, build)

    @Single(binds = [SyncPayroll::class, SyncTask::class])
    fun syncPayroll(
        feed: SyncFeedRepository,
        remote: PayslipRepository,
        replicas: PayslipReplicaRepository,
        revisions: PayslipRevisionRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = SyncPayroll(feed, remote, replicas, revisions, sessions, workspaces, Clock.System, build)

    @Factory
    fun exportPayslip(
        pdf: PayslipPdfRepository,
        outputs: DocumentOutputRepository,
        replicas: PayslipReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ExportPayslip(pdf, outputs, replicas, sessions, workspaces, Clock.System, build)
}
