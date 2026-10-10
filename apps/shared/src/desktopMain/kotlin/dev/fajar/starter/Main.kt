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
import dev.fajar.starter.identity.data.sso.datasources.DesktopBrowserAuthorizationSource
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.securestorage.*
import kotlinx.coroutines.*
import org.koin.dsl.module
import org.koin.dsl.onClose

fun main(args: Array<String>) {
    val environment = BuildEnvironment.current
    val directory =
        java.io.File(System.getProperty("user.home"), ".fluent-starter/${environment.id}")
    val links = AppLinkChannel()
    args.firstOrNull()?.let(links::receive)
    val container =
        createAppContainer(
            createUserPreferences(directory, migrateLegacy = environment.id == "prod"),
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
            credentials =
                if (BuildRuntime.persistDesktopSession)
                    DesktopCredentialStore("fluent-starter.${environment.id}")
                else MemoryCredentialStore(),
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
            title = "Fluent Starter",
            state = rememberWindowState(width = 1100.dp, height = 820.dp),
        ) {
            StarterApp(container, links.links)
        }
    }
}
