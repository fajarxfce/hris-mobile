package dev.fajar.starter.leave.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.leave.domain.repositories.*
import dev.fajar.starter.leave.domain.usecases.*
import dev.fajar.starter.leave.domain.working.repositories.LeaveWorkingCopyRepository
import dev.fajar.starter.leave.domain.working.usecases.*
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.leave.data")
class LeaveDataModule {
    @Single @Named("leave-acquisition") fun leaveAcquisitionLock() = Mutex()

    @Single @Named("leave-content-key-creation") fun leaveContentKeyCreationLock() = Mutex()

    @Factory
    fun openWorkingCopy(
        copies: LeaveWorkingCopyRepository,
        reads: LeaveReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = OpenLeaveWorkingCopy(copies, reads, sessions, workspaces, Clock.System, build)

    @Factory
    fun saveWorkingCopy(
        copies: LeaveWorkingCopyRepository,
        reads: LeaveReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = SaveLeaveWorkingCopy(copies, reads, sessions, workspaces, Clock.System, build)

    @Factory
    fun discardWorkingCopy(
        copies: LeaveWorkingCopyRepository,
        reads: LeaveReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = DiscardLeaveWorkingCopy(copies, reads, sessions, workspaces, Clock.System, build)

    @Factory
    fun prepareSubmission(
        commands: LeaveSubmissionCommandRepository,
        copies: LeaveWorkingCopyRepository,
        reads: LeaveRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = PrepareLeaveSubmission(commands, sessions, workspaces, Clock.System, build, reads, copies)

    @Factory
    fun observeSubmission(
        commands: LeaveSubmissionCommandRepository,
        reads: LeaveRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveLeaveSubmission(commands, sessions, workspaces, Clock.System, build, reads)

    @Factory
    fun reviewSubmission(
        commands: LeaveSubmissionCommandRepository,
        copies: LeaveWorkingCopyRepository,
        remote: LeaveRepository,
        cache: LeaveRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) =
        ReviewLeaveSubmission(
            commands,
            remote,
            cache,
            sessions,
            workspaces,
            Clock.System,
            build,
            copies,
        )

    @Single(binds = [ReplayLeaveSubmission::class, SyncTask::class])
    fun replaySubmission(
        commands: LeaveSubmissionCommandRepository,
        remote: LeaveSubmissionRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ReplayLeaveSubmission(commands, remote, sessions, workspaces, Clock.System, build)

    @Factory
    fun loadLeaveTypes(
        remote: LeavePreparationRepository,
        reads: LeaveRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = LoadLeaveTypes(remote, sessions, workspaces, Clock.System, build, reads)

    @Factory
    fun previewLeaveRequest(
        remote: LeavePreparationRepository,
        reads: LeaveRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = PreviewLeaveRequest(remote, sessions, workspaces, Clock.System, build, reads)

    @Factory
    fun prepareAction(
        commands: LeaveCommandRepository,
        cache: LeaveRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = PrepareLeaveAction(commands, cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun observeAction(
        commands: LeaveCommandRepository,
        reads: LeaveRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveLeaveAction(commands, sessions, workspaces, Clock.System, build, reads)

    @Factory
    fun reviewAction(
        commands: LeaveCommandRepository,
        remote: LeaveRepository,
        cache: LeaveRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ReviewLeaveAction(commands, remote, cache, sessions, workspaces, Clock.System, build)

    @Single(binds = [ReplayLeaveAction::class, SyncTask::class])
    fun replayAction(
        commands: LeaveCommandRepository,
        remote: LeaveActionRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ReplayLeaveAction(commands, remote, sessions, workspaces, Clock.System, build)

    @Factory
    fun observeLeaveRequest(
        cache: LeaveRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveLeaveRequest(cache, sessions, workspaces, Clock.System, build)

    @Single
    fun loadLeaveRequest(
        remote: LeaveRepository,
        cache: LeaveRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        @Named("leave-acquisition") execution: Mutex,
    ) = LoadLeaveRequest(remote, cache, sessions, workspaces, Clock.System, build, execution)

    @Factory(binds = [ObserveLeave::class, ObserveCompanyData::class])
    fun observeLeave(
        replicas: LeaveReplicaRepository,
        cache: LeaveCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveLeave(replicas, cache, sessions, workspaces, Clock.System, build)

    @Single(binds = [SyncLeave::class, SyncTask::class, SyncCompanyData::class])
    fun syncLeave(
        feed: SyncFeedRepository,
        remote: LeaveRecordRepository,
        directory: LeaveRepository,
        cache: LeaveReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        @Named("leave-acquisition") execution: Mutex,
    ) =
        SyncLeave(
            feed,
            remote,
            directory,
            cache,
            sessions,
            workspaces,
            Clock.System,
            build,
            execution,
        )
}
