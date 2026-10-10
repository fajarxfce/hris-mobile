package dev.fajar.starter.notifications.domain.usecases

import dev.fajar.starter.notifications.domain.repositories.NotificationAccessRepository

class CheckNotificationAccess(private val repository: NotificationAccessRepository) {
    suspend operator fun invoke() = repository.check()
}
