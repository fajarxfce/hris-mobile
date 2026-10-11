package dev.fajar.starter.workforce.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.location.domain.repositories.LocationRepository
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.workforce.domain.attendance.repositories.*
import dev.fajar.starter.workforce.domain.attendance.usecases.*
import dev.fajar.starter.workforce.domain.repositories.*
import dev.fajar.starter.workforce.domain.usecases.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.workforce.data")
class WorkforceDataModule {
    @Factory
    fun observeAttendance(
        cache: AttendanceCacheRepository,
        commands: AttendanceCommandRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveAttendance(cache, commands, sessions, workspaces, Clock.System, build)

    @Single
    fun refreshAttendance(
        remote: WorkforceRepository,
        attendance: AttendanceRepository,
        cache: AttendanceCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = RefreshAttendance(remote, attendance, cache, sessions, workspaces, Clock.System, build)

    @Single
    fun captureAttendance(
        remote: AttendanceRepository,
        cache: AttendanceCacheRepository,
        commands: AttendanceCommandRepository,
        location: LocationRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) =
        CaptureAttendance(
            remote,
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
        commands: AttendanceCommandRepository,
        remote: AttendanceRepository,
        cache: AttendanceCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ReplayAttendance(commands, remote, cache, sessions, workspaces, Clock.System, build)

    @Factory
    fun dismissAttendance(
        commands: AttendanceCommandRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = DismissAttendanceRejection(commands, sessions, workspaces, Clock.System, build)

    @Factory
    fun observeCalendar(
        cache: WorkCalendarCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveWorkCalendar(cache, sessions, workspaces, Clock.System, build)

    @Single
    fun refreshCalendar(
        remote: WorkforceRepository,
        cache: WorkCalendarCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = RefreshWorkCalendar(remote, cache, sessions, workspaces, Clock.System, build)
}
