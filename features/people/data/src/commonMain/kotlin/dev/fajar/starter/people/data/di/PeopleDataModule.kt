package dev.fajar.starter.people.data.di

import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.people.domain.repositories.PeopleRepository
import dev.fajar.starter.people.domain.usecases.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.people.data")
class PeopleDataModule {
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

    @Factory
    fun profile(
        people: PeopleRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
    ) = LoadEmploymentProfile(people, sessions, workspaces)
}
