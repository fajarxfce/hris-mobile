package dev.fajar.hris

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.app.navigation.AppLinkChannel
import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.featureflags.data.datasources.AndroidFeatureFlagSource
import dev.fajar.starter.fileio.AndroidFileInputSource
import dev.fajar.starter.fileio.AndroidFileOutputSource
import dev.fajar.starter.identity.data.sso.datasources.AndroidBrowserAuthorizationSource
import dev.fajar.starter.location.data.datasources.AndroidLocationDataSource
import dev.fajar.starter.observability.*
import dev.fajar.starter.securestorage.AndroidCredentialStore
import dev.fajar.starter.security.data.lock.datasources.AndroidDeviceAuthenticationSource
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.worker.AndroidWorkScheduler
import dev.fajar.starter.worker.SyncWorkerFactory

class StarterApplication : Application(), Configuration.Provider {
    val links = AppLinkChannel()
    val environment = AppEnvironment.entries.single { it.id == BuildConfig.APP_ENVIRONMENT }
    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setWorkerFactory(SyncWorkerFactory { container.koin.getAll<SyncTask>() })
                .build()

    private val scheduler by lazy { AndroidWorkScheduler(WorkManager.getInstance(this)) }
    val container by lazy {
        createAppContainer(
            createUserPreferences(this),
            createAppDatabase(this),
            androidNotificationModule(this, environment),
            environment,
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
            selfProfileReplicaCredentials =
                AndroidCredentialStore(this, "self-profile-replica-content-key"),
            documentCatalogCredentials =
                AndroidCredentialStore(this, "document-catalog-content-key"),
            commandCredentials = AndroidCredentialStore(this, "command-content-key"),
            fileCredentials = AndroidCredentialStore(this, "file-content-key"),
            workspaceCredentials = AndroidCredentialStore(this, "workspace-content-key"),
            remoteFeatureFlags = AndroidFeatureFlagSource(this),
            deviceAuthentication = deviceAuthentication,
            browserAuthorization = browserAuthorization,
            platform = AppPlatform.Android,
            location = location,
            fileInputs = fileInputs,
            fileOutputs = fileOutputs,
        )
    }

    val browserAuthorization by lazy { AndroidBrowserAuthorizationSource(this) }
    val location by lazy { AndroidLocationDataSource(this) }
    val fileInputs by lazy { AndroidFileInputSource(this) }
    val fileOutputs by lazy { AndroidFileOutputSource(this) }

    val deviceAuthentication by lazy { AndroidDeviceAuthenticationSource(this) }

    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(AndroidCrashSink(this, enabled = !BuildConfig.DEBUG))
        PerformanceMonitoring.install(AndroidPerformanceSink(this, enabled = !BuildConfig.DEBUG))
        scheduler.installPeriodic(container.koin.getAll<SyncTask>().map { it.key }.toSet())
    }
}
