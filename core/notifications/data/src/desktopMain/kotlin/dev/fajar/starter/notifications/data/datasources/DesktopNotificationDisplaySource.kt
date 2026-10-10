package dev.fajar.starter.notifications.data.datasources

import dev.fajar.starter.notifications.data.dto.NotificationPayload
import dev.fajar.starter.notifications.data.errors.NotificationUnavailableException
import java.awt.Color
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class DesktopNotificationDisplaySource(private val open: (String) -> Unit) :
    NotificationDisplaySource {
    private var icon: TrayIcon? = null

    override suspend fun show(payload: NotificationPayload): Unit =
        suspendCancellableCoroutine { continuation ->
            SwingUtilities.invokeLater {
                if (!continuation.isActive) return@invokeLater
                try {
                    if (!SystemTray.isSupported()) throw NotificationUnavailableException()
                    val tray =
                        icon
                            ?: TrayIcon(
                                    BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB).apply {
                                        createGraphics().apply {
                                            color = Color(0, 103, 184)
                                            fillRoundRect(2, 2, 20, 20, 5, 5)
                                            dispose()
                                        }
                                    },
                                    "Fluent Starter",
                                )
                                .also {
                                    it.isImageAutoSize = true
                                    SystemTray.getSystemTray().add(it)
                                    icon = it
                                }
                    tray.actionListeners.forEach { tray.removeActionListener(it) }
                    tray.addActionListener { open(payload.destination) }
                    tray.displayMessage(payload.title, payload.body, TrayIcon.MessageType.INFO)
                    continuation.resume(Unit)
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            }
        }

    fun close() {
        SwingUtilities.invokeLater {
            icon?.let { SystemTray.getSystemTray().remove(it) }
            icon = null
        }
    }
}
