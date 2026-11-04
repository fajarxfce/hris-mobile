package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files

private fun spaceDirectoryDatabase(): SpaceDirectoryDatabaseOwner {
    val directory = Files.createTempDirectory("space-directory").toFile()
    return SpaceDirectoryDatabaseOwner(
        { createAppDatabase(directory) },
        {
            directory.deleteRecursively()
            Unit
        },
    )
}

class SpaceDirectoryStorageTest : SpaceDirectoryStorageContract() {
    override fun databaseOwner() = spaceDirectoryDatabase()
}

class SpaceDirectoryAdmissionStorageTest : SpaceDirectoryAdmissionStorageContract() {
    override fun databaseOwner() = spaceDirectoryDatabase()
}
