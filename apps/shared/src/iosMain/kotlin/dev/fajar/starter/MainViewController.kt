package dev.fajar.starter

import androidx.compose.ui.window.ComposeUIViewController
import dev.fajar.starter.app.StarterApp
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.app.navigation.AppLinkChannel
import dev.fajar.starter.app.work.InboxPushWorker
import dev.fajar.starter.app.work.NativePushWorker
import dev.fajar.starter.app.work.startForegroundSync
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.config.BuildEnvironment
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.featureflags.data.datasources.AppleFeatureFlagSource
import dev.fajar.starter.featureflags.data.datasources.AppleRemoteConfigClient
import dev.fajar.starter.fileio.AppleFileInputSource
import dev.fajar.starter.fileio.AppleFileOutputSource
import dev.fajar.starter.identity.data.firebase.datasources.AppleFirebaseClientOptionsSource
import dev.fajar.starter.identity.data.sso.datasources.AppleBrowserAuthorizationSource
import dev.fajar.starter.inbox.domain.push.usecases.QueueInboxPushSignal
import dev.fajar.starter.location.data.datasources.AppleLocationDataSource
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.observability.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.security.data.lock.datasources.AppleDeviceAuthenticationSource
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.worker.runSyncTask
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import org.koin.dsl.module

/** Swift's application delegate owns the container, callback scope, and incoming intents. */
class AppleAppHost(
    firebase: AppleFirebaseClient,
    remoteConfig: AppleRemoteConfigClient,
    credentials: AppleCredentialClient,
    inboxCredentials: AppleCredentialClient,
    calendarCredentials: AppleCredentialClient,
    leaveCredentials: AppleCredentialClient,
    leaveReplicaCredentials: AppleCredentialClient,
    overtimeReplicaCredentials: AppleCredentialClient,
    workforceReplicaCredentials: AppleCredentialClient,
    payrollReplicaCredentials: AppleCredentialClient,
    expenseReplicaCredentials: AppleCredentialClient,
    directoryReplicaCredentials: AppleCredentialClient,
    selfProfileReplicaCredentials: AppleCredentialClient,
    documentCatalogCredentials: AppleCredentialClient,
    commandCredentials: AppleCredentialClient,
    fileCredentials: AppleCredentialClient,
    workspaceCredentials: AppleCredentialClient,
    pushCredentials: AppleCredentialClient,
    inboxPushCredentials: AppleCredentialClient,
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
    private val fileInputs = AppleFileInputSource()
    private val fileOutputs = AppleFileOutputSource()
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
            inboxCredentials = AppleCredentialStore(inboxCredentials),
            calendarCredentials = AppleCredentialStore(calendarCredentials),
            leaveCredentials = AppleCredentialStore(leaveCredentials),
            leaveReplicaCredentials = AppleCredentialStore(leaveReplicaCredentials),
            overtimeReplicaCredentials = AppleCredentialStore(overtimeReplicaCredentials),
            workforceReplicaCredentials = AppleCredentialStore(workforceReplicaCredentials),
            payrollReplicaCredentials = AppleCredentialStore(payrollReplicaCredentials),
            expenseReplicaCredentials = AppleCredentialStore(expenseReplicaCredentials),
            directoryReplicaCredentials = AppleCredentialStore(directoryReplicaCredentials),
            selfProfileReplicaCredentials = AppleCredentialStore(selfProfileReplicaCredentials),
            documentCatalogCredentials = AppleCredentialStore(documentCatalogCredentials),
            commandCredentials = AppleCredentialStore(commandCredentials),
            fileCredentials = AppleCredentialStore(fileCredentials),
            workspaceCredentials = AppleCredentialStore(workspaceCredentials),
            pushCredentials = AppleCredentialStore(pushCredentials),
            inboxPushCredentials = AppleCredentialStore(inboxPushCredentials),
            deviceAuthentication = AppleDeviceAuthenticationSource(),
            browserAuthorization = AppleBrowserAuthorizationSource(),
            firebaseOptions = AppleFirebaseClientOptionsSource(),
            remoteFeatureFlags = AppleFeatureFlagSource(remoteConfig),
            platform = AppPlatform.Ios,
            location = AppleLocationDataSource(),
            fileInputs = fileInputs,
            fileOutputs = fileOutputs,
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
                            .sortedBy { if (it.key == NativePushWorker.KEY) 0 else 1 }
                            .map { runSyncTask(it) }
                            .all { it == SyncResult.Complete }
                } finally {
                    completion(complete)
                }
            }
        return AppleSyncExecution(execution)
    }

    fun viewController() =
        ComposeUIViewController { StarterApp(container, links.links) }
            .also(fileInputs::attach)
            .also(fileOutputs::attach)

    fun openLink(uri: String) {
        links.receive(uri)
    }

    fun openNotification(destination: String) {
        links.receive("${BuildEnvironment.current.linkScheme}://app/$destination")
    }

    fun receivedToken(token: String) {
        push.receivedToken(token)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(3.seconds) {
                container.koin.get<SyncScheduleRepository>().request(NativePushWorker.KEY)
            }
        }
    }

    fun receivedInboxPush(
        fields: Map<String, String>,
        process: Boolean,
        completion: (Boolean) -> Unit,
    ): AppleSyncExecution {
        val job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var progressed = false
                try {
                    progressed =
                        withTimeoutOrNull(25.seconds) {
                            val queued = container.koin.get<QueueInboxPushSignal>()(fields)
                            if (queued !is AppResult.Success || !queued.value)
                                return@withTimeoutOrNull false
                            if (!process) return@withTimeoutOrNull true
                            when (val result = runSyncTask(container.koin.get<InboxPushWorker>())) {
                                SyncResult.Complete -> true
                                is SyncResult.Retry -> result.failure == null
                                is SyncResult.Blocked -> false
                            }
                        } ?: false
                } finally {
                    completion(progressed)
                }
            }
        return AppleSyncExecution(job)
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
