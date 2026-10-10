package dev.fajar.starter.notifications.domain.usecases

import dev.fajar.starter.notifications.domain.repositories.NotificationRepository

class MarkNotificationRead(private val repository: NotificationRepository) {
    suspend operator fun invoke(id: String) = repository.markRead(id)
}
