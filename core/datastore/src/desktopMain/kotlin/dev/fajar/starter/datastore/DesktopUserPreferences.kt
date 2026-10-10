package dev.fajar.starter.datastore

import androidx.datastore.core.okio.OkioStorage
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath

fun createUserPreferences(directory: File, migrateLegacy: Boolean = false): UserPreferencesStore =
    ProtoUserPreferencesStore(
        OkioStorage(FileSystem.SYSTEM, UserPreferencesSerializer) {
            directory.resolve("user_preferences.pb").absolutePath.toPath()
        },
        migrations =
            listOf(
                LegacyOnboardingMigration {
                    migrateLegacy &&
                        java.util.prefs.Preferences.userRoot()
                            .node("dev/fajar/fluent-starter")
                            .getBoolean("onboarding.complete", false)
                }
            ),
    )
