package dev.fajar.starter.security.domain.access.entities

/** Server-issued capabilities. Roles are descriptive; the client never invents their grants. */
enum class Permission(val key: String) {
    SaveActivity("activity.save"),
    UploadFile("files.upload"),
    DownloadFile("files.download"),
}
