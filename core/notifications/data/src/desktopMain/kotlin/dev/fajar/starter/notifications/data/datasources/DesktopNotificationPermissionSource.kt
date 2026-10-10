package dev.fajar.starter.notifications.data.datasources

import dev.fajar.starter.notifications.data.dto.NotificationPermission
import java.awt.SystemTray

class DesktopNotificationPermissionSource : NotificationPermissionSource {
    override suspend fun check() =
        if (SystemTray.isSupported()) NotificationPermission.Granted
        else NotificationPermission.Unsupported

    override suspend fun request() = check()
}
