@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import kotlin.uuid.Uuid

private fun spaceDirectoryDatabase(): SpaceDirectoryDatabaseOwner {
    val namespace = "space-directory-${Uuid.random()}"
    return SpaceDirectoryDatabaseOwner({ createAppDatabase(namespace) })
}

class SpaceDirectoryStorageTest : SpaceDirectoryStorageContract() {
    override fun databaseOwner() = spaceDirectoryDatabase()
}

class SpaceDirectoryAdmissionStorageTest : SpaceDirectoryAdmissionStorageContract() {
    override fun databaseOwner() = spaceDirectoryDatabase()
}
