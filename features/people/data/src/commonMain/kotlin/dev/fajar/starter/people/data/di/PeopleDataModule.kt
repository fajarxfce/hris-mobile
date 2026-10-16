package dev.fajar.starter.people.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.people.domain.profile.repositories.*
import dev.fajar.starter.people.domain.profile.usecases.*
import dev.fajar.starter.people.domain.replica.repositories.*
import dev.fajar.starter.people.domain.replica.usecases.*
import dev.fajar.starter.people.domain.repositories.PeopleRepository
import dev.fajar.starter.people.domain.usecases.*
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import kotlin.time.Clock
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.people.data")
class PeopleDataModule {
    @Factory(binds = [ObserveSelfProfiles::class, ObserveCompanyData::class])
    fun observeSelfProfiles(
        replicas: SelfProfileReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveSelfProfiles(replicas, sessions, workspaces, Clock.System, build)

    @Single(binds = [SyncSelfProfiles::class, SyncTask::class, SyncCompanyData::class])
    fun syncSelfProfiles(
        feed: SyncFeedRepository,
        remote: SelfProfileRecordRepository,
        replicas: SelfProfileReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = SyncSelfProfiles(feed, remote, replicas, sessions, workspaces, Clock.System, build)

    @Factory(binds = [ObserveCompanyDirectory::class, ObserveCompanyData::class])
    fun observeCompanyDirectory(
        replicas: DirectoryReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveCompanyDirectory(replicas, sessions, workspaces, Clock.System, build)

    @Single(binds = [SyncCompanyDirectory::class, SyncTask::class, SyncCompanyData::class])
    fun syncCompanyDirectory(
        feed: SyncFeedRepository,
        remote: DirectoryRecordRepository,
        replicas: DirectoryReplicaRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = SyncCompanyDirectory(feed, remote, replicas, sessions, workspaces, Clock.System, build)

    @Factory
    fun directoryDepartments(
        people: PeopleRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
    ) = LoadDirectoryDepartments(people, sessions, workspaces)

    @Factory
    fun companyDirectory(
        people: PeopleRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
    ) = LoadCompanyDirectory(people, sessions, workspaces)

    @Factory
    fun directory(
        people: PeopleRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
    ) = LoadSelfEmployments(people, sessions, workspaces)
}
