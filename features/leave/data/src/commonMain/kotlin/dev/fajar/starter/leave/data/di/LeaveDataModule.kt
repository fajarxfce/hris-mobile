package dev.fajar.starter.leave.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.leave.domain.repositories.*
import dev.fajar.starter.leave.domain.usecases.*
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.leave.data")
class LeaveDataModule {
    @Factory
    fun prepareSubmission(
        commands: LeaveSubmissionCommandRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = PrepareLeaveSubmission(commands, sessions, workspaces, Clock.System, build)

    @Factory
    fun observeSubmission(
        commands: LeaveSubmissionCommandRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveLeaveSubmission(commands, sessions, workspaces, Clock.System, build)

    @Factory
    fun reviewSubmission(
        commands: LeaveSubmissionCommandRepository,
        remote: LeaveRepository,
        cache: LeaveRequestCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ReviewLeaveSubmission(commands, remote, cache, sessions, workspaces, Clock.System, build)

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
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = LoadLeaveTypes(remote, sessions, workspaces, Clock.System, build)

    @Factory
    fun previewLeaveRequest(
        remote: LeavePreparationRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = PreviewLeaveRequest(remote, sessions, workspaces, Clock.System, build)

    @Factory
    fun prepareAction(
        commands: LeaveCommandRepository,
        cache: LeaveRequestCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = PrepareLeaveAction(commands, cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun observeAction(
        commands: LeaveCommandRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveLeaveAction(commands, sessions, workspaces, Clock.System, build)

    @Factory
    fun reviewAction(
        commands: LeaveCommandRepository,
        remote: LeaveRepository,
        cache: LeaveRequestCacheRepository,
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
        cache: LeaveRequestCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveLeaveRequest(cache, sessions, workspaces, Clock.System, build)

    @Single
    fun refreshLeaveRequest(
        remote: LeaveRepository,
        cache: LeaveRequestCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = RefreshLeaveRequest(remote, cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun observeLeave(
        replicas: LeaveReplicaRepository,
        cache: LeaveCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveLeave(replicas, cache, sessions, workspaces, Clock.System, build)

    @Single(binds = [SyncLeave::class, SyncTask::class])
    fun syncLeave(
        feed: SyncFeedRepository,
        remote: LeaveRecordRepository,
        directory: LeaveRepository,
        cache: LeaveReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = SyncLeave(feed, remote, directory, cache, sessions, workspaces, Clock.System, build)
}
