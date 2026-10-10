@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.fajar.starter.database

import androidx.room.Room
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

fun createAppDatabase(): AppDatabase {
    val directory =
        NSFileManager.defaultManager.URLForDirectory(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            null,
            true,
            null,
        ) ?: error("Application support directory is unavailable.")
    return RoomAppDatabase(
        Room.databaseBuilder<StarterDatabase>(requireNotNull(directory.path) + "/starter.db")
    )
}
