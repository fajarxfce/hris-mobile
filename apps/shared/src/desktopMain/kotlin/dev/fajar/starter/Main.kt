package dev.fajar.starter

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.fajar.starter.app.StarterApp
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.app.navigation.AppLinkChannel
import dev.fajar.starter.app.work.startForegroundSync
import dev.fajar.starter.common.config.BuildEnvironment
import dev.fajar.starter.common.config.BuildRuntime
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.fileio.DesktopFileInputSource
import dev.fajar.starter.fileio.DesktopFileOutputSource
import dev.fajar.starter.identity.data.sso.datasources.DesktopBrowserAuthorizationSource
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.securestorage.*
import kotlinx.coroutines.*
import org.koin.dsl.module
import org.koin.dsl.onClose

fun main(args: Array<String>) {
    val environment = BuildEnvironment.current
    val directory = java.io.File(System.getProperty("user.home"), ".hris-mobile/${environment.id}")
    val namespace = "hris-mobile.${environment.id}"
    val links = AppLinkChannel()
    args.firstOrNull()?.let(links::receive)
    val container =
        createAppContainer(
            createUserPreferences(directory, migrateLegacy = false),
            createAppDatabase(directory),
            module {
                single<NotificationPermissionSource> { DesktopNotificationPermissionSource() }
                single<NotificationDisplaySource> {
                        DesktopNotificationDisplaySource {
                            links.receive("${environment.linkScheme}://app/$it")
                        }
                    }
                    .onClose { (it as? DesktopNotificationDisplaySource)?.close() }
                single<PushTokenSource> { UnavailablePushTokenSource() }
            },
            browserAuthorization = DesktopBrowserAuthorizationSource(),
            credentials = desktopSessionCredentials(namespace),
            inboxCredentials = desktopSessionCredentials(namespace, "inbox-content-key"),
            commandCredentials = DesktopCredentialStore(namespace, "command-content-key"),
            fileCredentials = DesktopCredentialStore(namespace, "file-content-key"),
            fileInputs = DesktopFileInputSource(),
            fileOutputs = DesktopFileOutputSource(),
            calendarCredentials = desktopSessionCredentials(namespace, "calendar-content-key"),
            leaveReplicaCredentials =
                desktopSessionCredentials(namespace, "leave-replica-content-key"),
            overtimeReplicaCredentials =
                desktopSessionCredentials(namespace, "overtime-replica-content-key"),
            workforceReplicaCredentials =
                desktopSessionCredentials(namespace, "workforce-replica-content-key"),
            payrollReplicaCredentials =
                desktopSessionCredentials(namespace, "payroll-replica-content-key"),
            expenseReplicaCredentials =
                desktopSessionCredentials(namespace, "expense-replica-content-key"),
            directoryReplicaCredentials =
                desktopSessionCredentials(namespace, "directory-replica-content-key"),
            selfProfileReplicaCredentials =
                desktopSessionCredentials(namespace, "self-profile-replica-content-key"),
            documentCatalogCredentials =
                desktopSessionCredentials(namespace, "document-catalog-content-key"),
            leaveCredentials = desktopSessionCredentials(namespace, "leave-content-key"),
            workspaceCredentials = desktopSessionCredentials(namespace, "workspace-content-key"),
            pushCredentials = desktopSessionCredentials(namespace, "native-push-state"),
        )
    val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    startForegroundSync(container, workerScope)
    application {
        DisposableEffect(container) {
            onDispose {
                workerScope.cancel()
                links.close()
                container.close()
            }
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = "HRIS Mobile",
            state = rememberWindowState(width = 1100.dp, height = 820.dp),
        ) {
            StarterApp(container, links.links)
        }
    }
}

/** Session-owned read keys follow the desktop host's explicit persistence setting. */
private fun desktopSessionCredentials(
    namespace: String,
    recordName: String = "session",
): AtomicCredentialStore =
    if (BuildRuntime.persistDesktopSession) DesktopCredentialStore(namespace, recordName)
    else MemoryCredentialStore()
