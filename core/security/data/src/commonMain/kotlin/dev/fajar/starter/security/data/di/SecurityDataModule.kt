package dev.fajar.starter.security.data.di

import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.security.domain.access.repositories.AccessRepository
import dev.fajar.starter.security.domain.access.usecases.*
import dev.fajar.starter.security.domain.lock.repositories.*
import dev.fajar.starter.security.domain.lock.usecases.*
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.security.data")
class SecurityDataModule {
    @Factory
    fun observeLock(sessions: SessionRepository, locks: AppLockRepository) =
        ObserveAppLock(sessions, locks)

    @Factory
    fun unlock(
        sessions: SessionRepository,
        locks: AppLockRepository,
        device: DeviceAuthenticationRepository,
    ) = UnlockApp(sessions, locks, device)

    @Factory
    fun configureLock(
        sessions: SessionRepository,
        locks: AppLockRepository,
        device: DeviceAuthenticationRepository,
    ) = SetAppLockEnabled(sessions, locks, device)

    @Factory
    fun checkDevice(device: DeviceAuthenticationRepository) = CheckDeviceAuthentication(device)

    @Factory fun lock(locks: AppLockRepository) = LockApp(locks)

    @Factory
    fun interaction(sessions: SessionRepository, locks: AppLockRepository) =
        RecordAppInteraction(sessions, locks)

    @Factory
    fun resetLockedSession(sessions: SessionRepository, locks: AppLockRepository) =
        ResetLockedSession(sessions, locks)

    @Factory
    fun permission(sessions: SessionRepository, access: AccessRepository) =
        ObservePermission(sessions, access)

    @Factory
    fun observeAccess(sessions: SessionRepository, access: AccessRepository) =
        ObserveAccess(sessions, access)

    @Single
    fun refreshAccess(sessions: SessionRepository, access: AccessRepository) =
        RefreshAccess(sessions, access)
}
