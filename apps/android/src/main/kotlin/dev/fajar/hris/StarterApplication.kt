package dev.fajar.hris

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.Configuration
import androidx.work.WorkManager
import dev.fajar.inspector.NetworkInspection
import dev.fajar.inspector.matrix.captureMatrixHttp
import dev.fajar.inspector.matrix.rust.observeNetworkRequests
import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.app.inspection.ApplicationNetworkInspection
import dev.fajar.starter.app.navigation.AppLinkChannel
import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.app.work.CollaborationDeliveryWorker
import dev.fajar.starter.app.work.CollaborationRecoveryWorker
import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationHttpObservation
import dev.fajar.starter.collaboration.data.di.androidCollaborationRuntimeRepository
import dev.fajar.starter.collaboration.data.di.androidCollaborationSdkInventoryRepository
import dev.fajar.starter.collaboration.data.di.androidCollaborationSdkPossessionRepository
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkEndpoint
import dev.fajar.starter.collaboration.domain.entities.CollaborationRecoveryWake
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationText
import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.featureflags.data.datasources.AndroidFeatureFlagSource
import dev.fajar.starter.fileio.AndroidFileInputSource
import dev.fajar.starter.fileio.AndroidFileOutputSource
import dev.fajar.starter.identity.data.firebase.datasources.AndroidFirebaseClientOptionsSource
import dev.fajar.starter.identity.data.firebase.datasources.AndroidGoogleCredentialSource
import dev.fajar.starter.identity.data.sso.datasources.AndroidBrowserAuthorizationSource
import dev.fajar.starter.location.data.datasources.AndroidLocationDataSource
import dev.fajar.starter.notifications.data.datasources.AndroidActionNotificationIntents
import dev.fajar.starter.observability.*
import dev.fajar.starter.securestorage.AndroidCredentialStore
import dev.fajar.starter.security.data.lock.datasources.AndroidDeviceAuthenticationSource
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.worker.AndroidWorkScheduler
import dev.fajar.starter.worker.ForegroundSyncWorker
import dev.fajar.starter.worker.ForegroundWorkScheduler
import dev.fajar.starter.worker.RequestedSyncWorker
import dev.fajar.starter.worker.SyncWorkerFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.core.parameter.parametersOf

class StarterApplication : Application(), Configuration.Provider, AndroidCollaborationActionHost {
    val links = AppLinkChannel()
    val notificationIntents by lazy {
        AndroidActionNotificationIntents(
            this,
            "chat",
            ComponentName(this, MainActivity::class.java),
            ComponentName(this, AndroidCollaborationActionReceiver::class.java),
        )
    }
    val environment = AppEnvironment.entries.single { it.id == BuildConfig.APP_ENVIRONMENT }
    private val networkInspection =
        if (environment == AppEnvironment.Dev) NetworkInspection() else null
    private var networkInspectionShortcut: NetworkInspectionShortcut? = null

    fun openNetworkInspector() {
        container.koin.getOrNull<ApplicationNetworkInspection>()?.launchRequests?.request()
    }

    fun refreshNetworkInspectorShortcut() {
        // A background service may recreate the process and restore its session before a tap.
        // Let the Activity resume before attaching the OS shortcut to those capture boundaries.
        if (networkInspectionShortcut == null) {
            networkInspectionShortcut =
                networkInspection?.let {
                    NetworkInspectionShortcut(
                        this,
                        it,
                        ProcessLifecycleOwner.get().lifecycle.coroutineScope,
                    )
                }
        }
        networkInspectionShortcut?.refresh()
    }

    private val notificationRuntime = lazy {
        val koin = container.koin
        AndroidCollaborationNotificationRuntime(
            this,
            "chat",
            R.drawable.ic_notification,
            notificationIntents,
            koin.get(),
            createHost = { source, copy -> koin.get { parametersOf(source, copy) } },
            replyCallbacks = koin.get(),
            actionCallbacks = koin.get(),
        )
    }
    override val notificationActions
        get() = notificationRuntime.value.actions

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setWorkerFactory(SyncWorkerFactory { container.koin.getAll<SyncTask>() })
                .build()

    private val foregroundRequests =
        ForegroundWorkScheduler(
            setOf(QueueCollaborationText.DELIVERY_KEY, CollaborationRecoveryWake.WORK_KEY)
        )
    private var foregroundCollaboration: Job? = null

    private val scheduler by lazy {
        AndroidWorkScheduler(
            WorkManager.getInstance(this),
            collapsedKeys =
                setOf(CollaborationRecoveryWake.WORK_KEY, QueueCollaborationText.DELIVERY_KEY),
            onRequested = { foregroundRequests.notify(it) },
        )
    }
    val container by lazy {
        createAppContainer(
            createUserPreferences(this),
            createAppDatabase(this),
            androidNotificationModule(this, environment),
            environment,
            networkInspection = networkInspection,
            workScheduler = scheduler,
            credentials = AndroidCredentialStore(this),
            inboxCredentials = AndroidCredentialStore(this, "inbox-content-key"),
            calendarCredentials = AndroidCredentialStore(this, "calendar-content-key"),
            leaveCredentials = AndroidCredentialStore(this, "leave-content-key"),
            leaveReplicaCredentials = AndroidCredentialStore(this, "leave-replica-content-key"),
            workforceReplicaCredentials =
                AndroidCredentialStore(this, "workforce-replica-content-key"),
            overtimeReplicaCredentials =
                AndroidCredentialStore(this, "overtime-replica-content-key"),
            payrollReplicaCredentials = AndroidCredentialStore(this, "payroll-replica-content-key"),
            expenseReplicaCredentials = AndroidCredentialStore(this, "expense-replica-content-key"),
            directoryReplicaCredentials =
                AndroidCredentialStore(this, "directory-replica-content-key"),
            collaborationListCredentials =
                AndroidCredentialStore(this, "collaboration-list-content-key"),
            collaborationSpaceDirectoryCredentials =
                AndroidCredentialStore(this, "collaboration-space-directory-content-key"),
            collaborationReceiptCredentials =
                AndroidCredentialStore(this, "collaboration-receipt-content-key"),
            collaborationHistoryCredentials =
                AndroidCredentialStore(this, "collaboration-history-content-key"),
            collaborationNotificationInputCredentials =
                AndroidCredentialStore(this, "collaboration-notification-input-key"),
            selfProfileReplicaCredentials =
                AndroidCredentialStore(this, "self-profile-replica-content-key"),
            documentCatalogCredentials =
                AndroidCredentialStore(this, "document-catalog-content-key"),
            commandCredentials = AndroidCredentialStore(this, "command-content-key"),
            fileCredentials = AndroidCredentialStore(this, "file-content-key"),
            workspaceCredentials = AndroidCredentialStore(this, "workspace-content-key"),
            pushCredentials = AndroidCredentialStore(this, "native-push-state"),
            inboxPushCredentials = AndroidCredentialStore(this, "inbox-push-queue"),
            remoteFeatureFlags = AndroidFeatureFlagSource(this),
            deviceAuthentication = deviceAuthentication,
            browserAuthorization = browserAuthorization,
            firebaseOptions = AndroidFirebaseClientOptionsSource(this),
            googleCredentials = googleCredentials,
            platform = AppPlatform.Android,
            location = location,
            fileInputs = fileInputs,
            fileOutputs = fileOutputs,
            collaborationSdkInventory = { server ->
                androidCollaborationSdkInventoryRepository(
                    this,
                    server,
                    CollaborationSdkEndpoint.fromApiBaseUrl(
                        server.audience,
                        allowCleartext = environment == AppEnvironment.Dev,
                    ),
                )
            },
            collaborationSdkPossession = { server ->
                androidCollaborationSdkPossessionRepository(
                    this,
                    server,
                    CollaborationSdkEndpoint.fromApiBaseUrl(
                        server.audience,
                        allowCleartext = environment == AppEnvironment.Dev,
                    ),
                )
            },
            collaborationRuntime = { server, http ->
                androidCollaborationRuntimeRepository(
                    this,
                    server,
                    CollaborationSdkEndpoint.fromApiBaseUrl(
                        server.audience,
                        allowCleartext = environment == AppEnvironment.Dev,
                    ),
                    http,
                    httpObservation =
                        networkInspection?.let { inspection ->
                            AndroidCollaborationHttpObservation { client ->
                                captureMatrixHttp(inspection) { client.observeNetworkRequests() }
                            }
                        },
                )
            },
        )
    }

    val browserAuthorization by lazy { AndroidBrowserAuthorizationSource(this) }
    val googleCredentials by lazy { AndroidGoogleCredentialSource(this) }
    val location by lazy { AndroidLocationDataSource(this) }
    val fileInputs by lazy { AndroidFileInputSource(this) }
    val fileOutputs by lazy { AndroidFileOutputSource(this) }

    val deviceAuthentication by lazy { AndroidDeviceAuthenticationSource(this) }

    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(AndroidCrashSink(this, enabled = !BuildConfig.DEBUG))
        PerformanceMonitoring.install(AndroidPerformanceSink(this, enabled = !BuildConfig.DEBUG))
        scheduler.installPeriodic(container.koin.getAll<SyncTask>().map { it.key }.toSet())
        container.koin.get<ApplicationCollaborationLifetime>().start()
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        foregroundCollaboration =
            lifecycle.coroutineScope.launch(Dispatchers.Default) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    RequestedSyncWorker(
                            listOf(container.koin.get<CollaborationRecoveryWorker>()),
                            foregroundRequests,
                        )
                        .start(this)
                    ForegroundSyncWorker(
                            listOf(container.koin.get<CollaborationDeliveryWorker>()),
                            foregroundRequests,
                            retryDelay = 1.seconds,
                        )
                        .start(this)
                        .join()
                }
            }
        notificationRuntime.value.start()
    }

    override fun onTerminate() {
        foregroundCollaboration?.cancel()
        foregroundRequests.close()
        networkInspectionShortcut?.close()
        if (notificationRuntime.isInitialized()) notificationRuntime.value.close()
        container.close()
        super.onTerminate()
    }
}
