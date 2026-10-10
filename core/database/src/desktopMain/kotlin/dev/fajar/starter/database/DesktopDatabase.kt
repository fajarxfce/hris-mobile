package dev.fajar.starter.database

import androidx.room.Room
import java.io.File

fun createAppDatabase(directory: File): AppDatabase {
    check(directory.isDirectory || directory.mkdirs()) { "Database directory is unavailable." }
    return RoomAppDatabase(
        Room.databaseBuilder<StarterDatabase>(directory.resolve("starter.db").absolutePath)
    )
}
