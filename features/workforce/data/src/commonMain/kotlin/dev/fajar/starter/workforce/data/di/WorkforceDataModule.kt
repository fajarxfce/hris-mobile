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
import dev.fajar.starter.workforce.domain.overtime.working.repositories.*
import dev.fajar.starter.workforce.domain.overtime.working.usecases.*
import dev.fajar.starter.workforce.domain.replica.repositories.*
import dev.fajar.starter.workforce.domain.replica.usecases.SyncWorkforce
import dev.fajar.starter.workforce.domain.repositories.*
import dev.fajar.starter.workforce.domain.usecases.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import dev.fajar.starter.workspace.domain.usecases.ObservePendingActions
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.workforce.data")
class WorkforceDataModule {
    @Single @Named("overtime-content-key-creation") fun overtimeContentKeyCreation() = Mutex()

    @Factory
    fun openOvertimeActionWorkingCopy(
        copies: OvertimeActionWorkingCopyRepository,
        reads: OvertimeRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = OpenOvertimeActionWorkingCopy(copies, reads, sessions, workspaces, Clock.System, build)

    @Factory
    fun saveOvertimeActionWorkingCopy(
        copies: OvertimeActionWorkingCopyRepository,
        reads: OvertimeRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = SaveOvertimeActionWorkingCopy(copies, reads, sessions, workspaces, Clock.System, build)

    @Factory
    fun discardOvertimeActionWorkingCopy(
        copies: OvertimeActionWorkingCopyRepository,
        reads: OvertimeRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = DiscardOvertimeActionWorkingCopy(copies, reads, sessions, workspaces, Clock.System, build)

    @Factory
    fun openOvertimePlanWorkingCopy(
        copies: OvertimePlanWorkingCopyRepository,
        reads: OvertimeReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = OpenOvertimePlanWorkingCopy(copies, reads, sessions, workspaces, Clock.System, build)

    @Factory
    fun saveOvertimePlanWorkingCopy(
        copies: OvertimePlanWorkingCopyRepository,
        reads: OvertimeReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = SaveOvertimePlanWorkingCopy(copies, reads, sessions, workspaces, Clock.System, build)

    @Factory
    fun discardOvertimePlanWorkingCopy(
        copies: OvertimePlanWorkingCopyRepository,
        reads: OvertimeReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = DiscardOvertimePlanWorkingCopy(copies, reads, sessions, workspaces, Clock.System, build)

    @Factory
    fun prepareOvertimePlan(
        commands: OvertimeCommandRepository,
        cache: OvertimeRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        copies: OvertimePlanWorkingCopyRepository,
    ) = PrepareOvertimePlan(commands, cache, sessions, workspaces, Clock.System, build, copies)

    @Factory
    fun prepareOvertimeAction(
        commands: OvertimeCommandRepository,
        cache: OvertimeRequestReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        copies: OvertimeActionWorkingCopyRepository,
    ) = PrepareOvertimeAction(commands, cache, sessions, workspaces, Clock.System, build, copies)

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
        copies: OvertimePlanWorkingCopyRepository,
        actionCopies: OvertimeActionWorkingCopyRepository,
    ) =
        ReviewOvertimeCommand(
            commands,
            cache,
            sessions,
            workspaces,
            Clock.System,
            build,
            copies,
            actionCopies,
        )

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

    @Factory(binds = [ObserveOvertime::class, ObserveCompanyData::class])
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

    @Single(binds = [SyncOvertime::class, SyncTask::class, SyncCompanyData::class])
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

    @Single(binds = [SyncWorkforce::class, SyncTask::class, SyncCompanyData::class])
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

    @Factory(binds = [ObserveWorkCalendar::class, ObserveCompanyData::class])
    fun observeCalendar(
        replicas: WorkforceReplicaRepository,
        cache: WorkCalendarCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveWorkCalendar(replicas, cache, sessions, workspaces, Clock.System, build)

    @Factory(binds = [ObservePendingActions::class])
    fun observeOvertimePending(
        plans: OvertimePlanWorkingCopyRepository,
        actions: OvertimeActionWorkingCopyRepository,
        commands: OvertimeCommandRepository,
        reads: OvertimeReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) =
        ObserveOvertimePending(
            plans,
            actions,
            commands,
            reads,
            sessions,
            workspaces,
            Clock.System,
            build,
        )

    @Factory(binds = [ObservePendingActions::class])
    fun observeAttendancePending(
        commands: AttendanceCommandRepository,
        reads: WorkforceReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveAttendancePending(commands, reads, sessions, workspaces, Clock.System, build)
}
