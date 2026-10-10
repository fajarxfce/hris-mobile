package dev.fajar.starter.notifications.data.mappers

import dev.fajar.starter.database.InboxRecord
import dev.fajar.starter.notifications.domain.entities.NotificationMessage

fun InboxRecord.toNotification() =
    NotificationMessage(id, title, body, destination, createdAtEpochMillis, read)

fun NotificationMessage.toRecord() =
    InboxRecord(id, title, body, destination, createdAtEpochMillis, read)
