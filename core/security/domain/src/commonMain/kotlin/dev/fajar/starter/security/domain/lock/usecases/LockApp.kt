package dev.fajar.starter.security.domain.lock.usecases

import dev.fajar.starter.security.domain.lock.repositories.AppLockRepository

class LockApp(private val locks: AppLockRepository) {
    suspend operator fun invoke() = locks.revoke()
}
