@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.fajar.starter.app.StarterApp
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.app.navigation.AppLinkChannel
import dev.fajar.starter.app.work.startForegroundSync
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.config.BuildEnvironment
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.featureflags.data.datasources.BrowserFeatureFlagSource
import dev.fajar.starter.identity.data.sso.datasources.WebBrowserAuthorizationSource
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.notifications.domain.entities.NotificationMessage
import dev.fajar.starter.notifications.domain.usecases.ReceiveNotification
import dev.fajar.starter.observability.DiagnosticArea
import dev.fajar.starter.observability.Diagnostics
import dev.fajar.starter.sync.data.datasources.WorkScheduler
import dev.fajar.starter.sync.domain.SyncTask
import kotlin.time.Clock
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import org.koin.dsl.module

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    if (dev.fajar.starter.identity.data.sso.datasources.handleOidcRedirect()) return
    val environment = BuildEnvironment.current
    val namespace = "fluent-starter.${environment.id}"
    val links = AppLinkChannel()
    val push = BrowserPushTokenSource()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val container =
        createAppContainer(
            createUserPreferences(namespace),
            createAppDatabase(namespace),
            module {
                single<NotificationPermissionSource> { BrowserNotificationPermissionSource() }
                single<NotificationDisplaySource> { BrowserNotificationDisplaySource() }
                single<PushTokenSource> { push }
            },
            environment,
            remoteFeatureFlags = BrowserFeatureFlagSource(),
            browserAuthorization = WebBrowserAuthorizationSource(),
            platform = AppPlatform.Web,
        )
    startForegroundSync(container, scope)
    window.addEventListener(
        "online",
        {
            scope.launch {
                container.koin.getAll<SyncTask>().forEach {
                    container.koin.get<WorkScheduler>().enqueue(it.key)
                }
            }
        },
    )
    val receive = container.koin.get<ReceiveNotification>()
    scope.launch {
        try {
            push.messages().collect { payload ->
                receive(
                    NotificationMessage(
                        payload.id,
                        payload.title,
                        payload.body,
                        payload.destination,
                        Clock.System.now().toEpochMilliseconds(),
                    )
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Diagnostics.failure(DiagnosticArea.Notification, error)
        }
    }
    links.receive(window.location.hash)
    window.addEventListener("hashchange", { links.receive(window.location.hash) })
    bindNotificationLinks(links::receive)
    onPageClosed {
        scope.cancel()
        links.close()
        container.close()
    }
    ComposeViewport(document.body!!) { StarterApp(container, links.links) }
}

@JsFun(
    """(receive) => {
    if (!('serviceWorker' in navigator)) return;
    navigator.serviceWorker.addEventListener('message', (event) => {
        if (event.data?.type !== 'open-link') return;
        const url = new URL(event.data.url, location.href);
        if (url.origin === location.origin && url.pathname === location.pathname) receive(url.hash);
    });
}"""
)
private external fun bindNotificationLinks(receive: (String) -> Unit)

@JsFun(
    "(close) => window.addEventListener('pagehide', event => { if (!event.persisted) close(); })"
)
private external fun onPageClosed(close: () -> Unit)
