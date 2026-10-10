package dev.fajar.starter.database

import android.content.Context
import androidx.room.Room

fun createAppDatabase(context: Context): AppDatabase =
    RoomAppDatabase(Room.databaseBuilder<StarterDatabase>(context.applicationContext, "starter.db"))
