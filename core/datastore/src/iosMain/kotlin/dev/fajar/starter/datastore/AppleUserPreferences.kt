@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.fajar.starter.datastore

import androidx.datastore.core.okio.OkioStorage
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask

fun createUserPreferences(): UserPreferencesStore =
    ProtoUserPreferencesStore(
        OkioStorage(FileSystem.SYSTEM, UserPreferencesSerializer) {
            val directory =
                NSFileManager.defaultManager.URLForDirectory(
                    NSApplicationSupportDirectory,
                    NSUserDomainMask,
                    null,
                    true,
                    null,
                ) ?: error("Application support directory is unavailable.")
            (requireNotNull(directory.path) + "/user_preferences.pb").toPath()
        },
        migrations =
            listOf(
                LegacyOnboardingMigration {
                    NSUserDefaults.standardUserDefaults.boolForKey("onboarding.complete")
                }
            ),
    )
