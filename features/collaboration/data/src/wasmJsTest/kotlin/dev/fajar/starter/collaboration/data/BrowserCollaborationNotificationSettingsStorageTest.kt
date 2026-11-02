@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import kotlin.uuid.Uuid

class BrowserCollaborationNotificationSettingsStorageTest :
    CollaborationNotificationSettingsStorageContract() {
    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val namespace = "collaboration-notifications-${Uuid.random()}"
        return CollaborationHistoryDatabaseOwner({ createAppDatabase(namespace) })
    }
}
