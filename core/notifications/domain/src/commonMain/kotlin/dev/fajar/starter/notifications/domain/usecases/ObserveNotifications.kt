package dev.fajar.starter.notifications.domain.usecases

import dev.fajar.starter.notifications.domain.repositories.NotificationRepository

class ObserveNotifications(private val repository: NotificationRepository) {
    operator fun invoke() = repository.observe()
}
