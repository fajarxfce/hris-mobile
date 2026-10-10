package dev.fajar.starter.notifications.presentation.inbox

import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import dev.fajar.starter.notifications.domain.entities.NotificationMessage

data class NotificationState(
    val messages: List<NotificationMessage> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val access: NotificationAccess? = null,
    val status: dev.fajar.starter.localization.AppString? = null,
    val error: dev.fajar.starter.common.result.Failure? = null,
)
