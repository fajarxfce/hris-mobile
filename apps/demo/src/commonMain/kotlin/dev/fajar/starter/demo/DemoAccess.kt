package dev.fajar.starter.demo

import kotlin.time.Clock
import kotlinx.serialization.json.*

internal fun demoAccess(editor: Boolean): String =
    buildJsonObject {
            putJsonArray("roles") { add(if (editor) "editor" else "viewer") }
            putJsonArray("permissions") {
                add("files.download")
                if (editor) {
                    add("activity.save")
                    add("files.upload")
                }
            }
            put("expiresAtEpochMillis", Clock.System.now().toEpochMilliseconds() + 3_600_000)
        }
        .toString()
