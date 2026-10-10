package dev.fajar.starter

import androidx.compose.ui.window.ComposeUIViewController
import dev.fajar.starter.app.StarterApp
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.app.navigation.AppLinkChannel
import dev.fajar.starter.app.work.startForegroundSync
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.config.BuildEnvironment
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.featureflags.data.datasources.AppleFeatureFlagSource
import dev.fajar.starter.featureflags.data.datasources.AppleRemoteConfigClient
import dev.fajar.starter.identity.data.sso.datasources.AppleBrowserAuthorizationSource
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.notifications.domain.entities.NotificationMessage
import dev.fajar.starter.notifications.domain.usecases.ReceiveNotification
import dev.fajar.starter.observability.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.security.data.lock.datasources.AppleDeviceAuthenticationSource
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.worker.runSyncTask
import kotlin.time.Clock
import kotlinx.coroutines.*
import org.koin.dsl.module

/** Swift's application delegate owns the container, callback scope, and incoming intents. */
class AppleAppHost(
    firebase: AppleFirebaseClient,
    remoteConfig: AppleRemoteConfigClient,
    credentials: AppleCredentialClient,
    crash: AppleCrashClient,
    performance: ApplePerformanceClient,
) {
    init {
        Diagnostics.install(AppleCrashSink(crash))
        PerformanceMonitoring.install(ApplePerformanceSink(performance))
    }

    private val links = AppLinkChannel()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val push = ApplePushTokenSource(firebase)
    private val container =
        createAppContainer(
            createUserPreferences(),
            createAppDatabase(),
            module {
                single<NotificationPermissionSource> { AppleNotificationPermissionSource() }
                single<NotificationDisplaySource> { AppleNotificationDisplaySource() }
                single<PushTokenSource> { push }
            },
            credentials = AppleCredentialStore(credentials),
            deviceAuthentication = AppleDeviceAuthenticationSource(),
            browserAuthorization = AppleBrowserAuthorizationSource(),
            remoteFeatureFlags = AppleFeatureFlagSource(remoteConfig),
            platform = AppPlatform.Ios,
        )

    private var foregroundWorker: Job? = null

    fun startForegroundWork() {
        if (foregroundWorker?.isActive == true) return
        foregroundWorker = startForegroundSync(container, scope)
    }

    fun stopForegroundWork() {
        foregroundWorker?.cancel()
        foregroundWorker = null
    }

    fun runBackgroundSync(completion: (Boolean) -> Unit): AppleSyncExecution {
        val execution =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var complete = false
                try {
                    complete =
                        container.koin
                            .getAll<SyncTask>()
                            .map { runSyncTask(it) }
                            .all { it == SyncResult.Complete }
                } finally {
                    completion(complete)
                }
            }
        return AppleSyncExecution(execution)
    }

    fun viewController() = ComposeUIViewController { StarterApp(container, links.links) }

    fun openLink(uri: String) {
        links.receive(uri)
    }

    fun openNotification(destination: String) {
        links.receive("${BuildEnvironment.current.linkScheme}://app/$destination")
    }

    fun receivedToken(token: String) {
        push.receivedToken(token)
    }

    fun receivedNotification(
        id: String,
        title: String,
        body: String,
        destination: String,
        completion: () -> Unit,
    ) {
        scope.launch {
            try {
                container.koin.get<ReceiveNotification>()(
                    NotificationMessage(
                        id,
                        title,
                        body,
                        destination,
                        Clock.System.now().toEpochMilliseconds(),
                    ),
                    systemDisplayed = true,
                )
            } finally {
                completion()
            }
        }
    }

    fun close() {
        scope.cancel()
        links.close()
        container.close()
    }
}

/** Swift cancels this handle when BGTaskScheduler expires the execution window. */
class AppleSyncExecution internal constructor(private val job: Job) {
    fun cancel() {
        job.cancel()
    }
}
