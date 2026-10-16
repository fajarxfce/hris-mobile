package dev.fajar.starter.workforce.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.location.domain.repositories.LocationRepository
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workforce.domain.attendance.repositories.*
import dev.fajar.starter.workforce.domain.attendance.usecases.*
import dev.fajar.starter.workforce.domain.overtime.repositories.*
import dev.fajar.starter.workforce.domain.overtime.usecases.*
import dev.fajar.starter.workforce.domain.replica.repositories.*
import dev.fajar.starter.workforce.domain.replica.usecases.SyncWorkforce
import dev.fajar.starter.workforce.domain.repositories.*
import dev.fajar.starter.workforce.domain.usecases.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.workforce.data")
class WorkforceDataModule {
    @Factory
    fun prepareOvertimePlan(
        commands: OvertimeCommandRepository,
        cache: OvertimeRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = PrepareOvertimePlan(commands, cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun prepareOvertimeAction(
        commands: OvertimeCommandRepository,
        cache: OvertimeRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = PrepareOvertimeAction(commands, cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun observeOvertimeCommand(
        commands: OvertimeCommandRepository,
        cache: OvertimeRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveOvertimeCommand(commands, cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun reviewOvertimeCommand(
        commands: OvertimeCommandRepository,
        cache: OvertimeRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ReviewOvertimeCommand(commands, cache, sessions, workspaces, Clock.System, build)

    @Single(binds = [ReplayOvertimeCommands::class, SyncTask::class])
    fun replayOvertimeCommands(
        commands: OvertimeCommandRepository,
        remote: OvertimeSubmissionRepository,
        cache: OvertimeRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ReplayOvertimeCommands(commands, remote, cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun reviewOvertimeSchedule(
        workforce: WorkforceRepository,
        remote: OvertimePlanningRepository,
        cache: OvertimeRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ReviewOvertimeSchedule(workforce, remote, cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun observeOvertime(
        replicas: OvertimeReplicaRepository,
        cache: OvertimeCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveOvertime(replicas, cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun observeOvertimeRequest(
        cache: OvertimeRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveOvertimeRequest(cache, sessions, workspaces, Clock.System, build)

    @Single @Named("overtime-acquisition") fun overtimeAcquisition() = Mutex()

    @Single(binds = [SyncOvertime::class, SyncTask::class])
    fun syncOvertime(
        feed: SyncFeedRepository,
        remote: OvertimeRecordRepository,
        workforce: WorkforceRepository,
        cache: OvertimeReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        @Named("overtime-acquisition") execution: Mutex,
    ) =
        SyncOvertime(
            feed,
            remote,
            workforce,
            cache,
            sessions,
            workspaces,
            Clock.System,
            build,
            execution,
        )

    @Single
    fun loadOvertimeRequest(
        workforce: WorkforceRepository,
        remote: OvertimeRepository,
        cache: OvertimeRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        @Named("overtime-acquisition") execution: Mutex,
    ) =
        LoadOvertimeRequest(
            workforce,
            remote,
            cache,
            sessions,
            workspaces,
            Clock.System,
            build,
            execution,
        )

    @Factory
    fun observeAttendance(
        replicas: WorkforceReplicaRepository,
        cache: AttendanceCacheRepository,
        commands: AttendanceCommandRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveAttendance(replicas, cache, commands, sessions, workspaces, Clock.System, build)

    @Single(binds = [SyncWorkforce::class, SyncTask::class])
    fun syncWorkforce(
        feed: SyncFeedRepository,
        remote: WorkforceRecordRepository,
        workforce: WorkforceRepository,
        replicas: WorkforceReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = SyncWorkforce(feed, remote, workforce, replicas, sessions, workspaces, Clock.System, build)

    @Single
    fun captureAttendance(
        remote: AttendanceRepository,
        replicas: WorkforceReplicaRepository,
        cache: AttendanceCacheRepository,
        commands: AttendanceCommandRepository,
        location: LocationRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) =
        CaptureAttendance(
            remote,
            replicas,
            cache,
            commands,
            location,
            sessions,
            workspaces,
            Clock.System,
            build,
        )

    @Single(binds = [ReplayAttendance::class, SyncTask::class])
    fun replayAttendance(
        replicas: WorkforceReplicaRepository,
        commands: AttendanceCommandRepository,
        remote: AttendanceRepository,
        cache: AttendanceCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) =
        ReplayAttendance(
            commands,
            remote,
            replicas,
            cache,
            sessions,
            workspaces,
            Clock.System,
            build,
        )

    @Factory
    fun dismissAttendance(
        commands: AttendanceCommandRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = DismissAttendanceRejection(commands, sessions, workspaces, Clock.System, build)

    @Factory
    fun observeCalendar(
        replicas: WorkforceReplicaRepository,
        cache: WorkCalendarCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveWorkCalendar(replicas, cache, sessions, workspaces, Clock.System, build)
}
