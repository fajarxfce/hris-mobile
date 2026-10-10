package dev.fajar.starter.identity.data.di

import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.identity.domain.sso.repositories.SsoRepository
import dev.fajar.starter.identity.domain.sso.usecases.ListSsoProviders
import dev.fajar.starter.identity.domain.sso.usecases.SignInWithSso
import dev.fajar.starter.identity.domain.usecases.*
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.identity.data")
class IdentityModule {
    @Factory fun providers(sso: SsoRepository) = ListSsoProviders(sso)

    @Single
    fun ssoSignIn(sso: SsoRepository, identity: IdentityRepository, sessions: SessionRepository) =
        SignInWithSso(sso, identity, sessions)

    @Factory fun validation() = ValidateSignIn()

    @Factory
    fun signIn(identity: IdentityRepository, sessions: SessionRepository) =
        SignIn(identity, sessions)

    @Factory fun signOut(sessions: SessionRepository) = SignOut(sessions)

    @Factory fun observeUser(sessions: SessionRepository) = ObserveUser(sessions)

    @Factory fun restore(sessions: SessionRepository) = RestoreSession(sessions)

    @Single
    fun tokens(identity: IdentityRepository, sessions: SessionRepository) =
        AcquireSessionTokens(identity, sessions)
}
