package dev.fajar.starter.identity.data.di

import dev.fajar.starter.identity.domain.repositories.IdentityRepository
import dev.fajar.starter.identity.domain.usecases.ObserveUser
import dev.fajar.starter.identity.domain.usecases.SignIn
import dev.fajar.starter.identity.domain.usecases.SignOut
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module

@Module
@ComponentScan("dev.fajar.starter.identity.data")
class IdentityModule {
    @Factory fun signIn(repository: IdentityRepository) = SignIn(repository)

    @Factory fun signOut(repository: IdentityRepository) = SignOut(repository)

    @Factory fun observeUser(repository: IdentityRepository) = ObserveUser(repository)
}
