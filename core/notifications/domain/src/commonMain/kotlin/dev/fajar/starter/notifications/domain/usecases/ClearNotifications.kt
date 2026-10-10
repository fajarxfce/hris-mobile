package dev.fajar.starter.notifications.domain.usecases

import dev.fajar.starter.notifications.domain.repositories.NotificationRepository

class ClearNotifications(private val repository: NotificationRepository) {
    suspend operator fun invoke() = repository.clear()
}
