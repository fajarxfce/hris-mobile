package dev.fajar.starter.identity.data.di

import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.identity.domain.sso.repositories.SsoRepository
import dev.fajar.starter.identity.domain.sso.usecases.ListSsoProviders
import dev.fajar.starter.identity.domain.sso.usecases.SignInWithSso
import dev.fajar.starter.identity.domain.usecases.*
import kotlinx.coroutines.sync.Mutex
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
    fun signIn(identity: IdentityRepository, sessions: SessionRepository, server: IdentityServer) =
        SignIn(identity, sessions, server)

    @Factory fun signOut(sessions: SessionRepository) = SignOut(sessions)

    @Factory fun observeUser(sessions: SessionRepository) = ObserveUser(sessions)

    @Factory fun observeIdentity(sessions: SessionRepository) = ObserveSessionIdentity(sessions)

    @Factory
    fun restore(sessions: SessionRepository, server: IdentityServer) =
        RestoreSession(sessions, server)

    @Single @Named("session-mutations") fun sessionMutations() = Mutex()

    @Factory
    fun account(sessions: SessionRepository, accounts: AccountRepository) =
        LoadAccount(sessions, accounts)

    @Factory
    fun beginMfa(
        sessions: SessionRepository,
        mfa: MfaRepository,
        server: IdentityServer,
        @Named("session-mutations") mutations: Mutex,
    ) = BeginMfaEnrollment(sessions, mfa, server, mutations)

    @Factory
    fun confirmMfa(
        sessions: SessionRepository,
        mfa: MfaRepository,
        server: IdentityServer,
        @Named("session-mutations") mutations: Mutex,
    ) = ConfirmMfaEnrollment(sessions, mfa, server, mutations)

    @Factory
    fun verifyMfa(
        sessions: SessionRepository,
        mfa: MfaRepository,
        server: IdentityServer,
        @Named("session-mutations") mutations: Mutex,
    ) = VerifyMfa(sessions, mfa, server, mutations)

    @Single
    fun tokens(
        identity: IdentityRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        @Named("session-mutations") mutations: Mutex,
    ) = AcquireSessionTokens(identity, sessions, server, refresh = mutations)
}
