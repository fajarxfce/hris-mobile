package dev.fajar.starter.datastore

import android.content.Context
import androidx.datastore.core.okio.OkioStorage
import okio.FileSystem
import okio.Path.Companion.toPath

fun createUserPreferences(context: Context): UserPreferencesStore =
    ProtoUserPreferencesStore(
        OkioStorage(FileSystem.SYSTEM, UserPreferencesSerializer) {
            context.applicationContext.filesDir.resolve("user_preferences.pb").absolutePath.toPath()
        },
        migrations =
            listOf(
                LegacyOnboardingMigration {
                    context
                        .getSharedPreferences("fluent-starter", Context.MODE_PRIVATE)
                        .getBoolean("onboarding.complete", false)
                }
            ),
    )
