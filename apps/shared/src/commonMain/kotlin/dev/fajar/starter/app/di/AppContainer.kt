package dev.fajar.starter.app.di

import dev.fajar.starter.auth.presentation.di.AuthPresentationModule
import dev.fajar.starter.availability.data.di.AvailabilityDataModule
import dev.fajar.starter.availability.presentation.di.AvailabilityPresentationModule
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.config.BuildEnvironment
import dev.fajar.starter.common.config.BuildRuntime
import dev.fajar.starter.database.AppDatabase
import dev.fajar.starter.datastore.UserPreferencesStore
import dev.fajar.starter.documents.data.di.DocumentsDataModule
import dev.fajar.starter.documents.presentation.di.DocumentsPresentationModule
import dev.fajar.starter.expenses.data.di.ExpenseDataModule
import dev.fajar.starter.expenses.presentation.di.ExpensePresentationModule
import dev.fajar.starter.featureflags.data.datasources.RemoteFeatureFlagSource
import dev.fajar.starter.featureflags.data.datasources.UnavailableFeatureFlagSource
import dev.fajar.starter.featureflags.data.di.FeatureFlagDataModule
import dev.fajar.starter.fileio.FileInputSource
import dev.fajar.starter.fileio.FileOutputSource
import dev.fajar.starter.fileio.UnavailableFileInputSource
import dev.fajar.starter.fileio.UnavailableFileOutputSource
import dev.fajar.starter.home.presentation.di.HomePresentationModule
import dev.fajar.starter.identity.data.di.IdentityModule
import dev.fajar.starter.identity.data.firebase.datasources.*
import dev.fajar.starter.identity.data.sso.config.SsoConfiguration
import dev.fajar.starter.identity.data.sso.datasources.BrowserAuthorizationSource
import dev.fajar.starter.identity.data.sso.datasources.UnavailableBrowserAuthorizationSource
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.inbox.data.di.InboxDataModule
import dev.fajar.starter.inbox.presentation.di.InboxPresentationModule
import dev.fajar.starter.leave.data.di.LeaveDataModule
import dev.fajar.starter.leave.presentation.di.LeavePresentationModule
import dev.fajar.starter.location.data.datasources.LocationDataSource
import dev.fajar.starter.location.data.datasources.UnavailableLocationDataSource
import dev.fajar.starter.location.data.di.LocationDataModule
import dev.fajar.starter.notifications.data.di.NotificationDataModule
import dev.fajar.starter.notifications.presentation.di.NotificationPresentationModule
import dev.fajar.starter.onboarding.data.di.OnboardingDataModule
import dev.fajar.starter.onboarding.presentation.di.OnboardingPresentationModule
import dev.fajar.starter.payroll.data.di.PayrollDataModule
import dev.fajar.starter.payroll.presentation.di.PayrollPresentationModule
import dev.fajar.starter.people.data.di.PeopleDataModule
import dev.fajar.starter.people.presentation.di.PeoplePresentationModule
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.security.data.di.SecurityDataModule
import dev.fajar.starter.security.data.lock.datasources.DeviceAuthenticationSource
import dev.fajar.starter.security.data.lock.datasources.UnavailableDeviceAuthenticationSource
import dev.fajar.starter.security.presentation.di.SecurityPresentationModule
import dev.fajar.starter.settings.data.di.SettingsDataModule
import dev.fajar.starter.settings.presentation.di.SettingsPresentationModule
import dev.fajar.starter.sync.data.datasources.WorkScheduler
import dev.fajar.starter.sync.data.di.SyncDataModule
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.transfers.data.datasources.TransferInputSource
import dev.fajar.starter.transfers.data.datasources.UnavailableTransferInputSource
import dev.fajar.starter.transfers.data.di.TransferDataModule
import dev.fajar.starter.worker.ForegroundWorkScheduler
import dev.fajar.starter.workforce.data.di.WorkforceDataModule
import dev.fajar.starter.workforce.presentation.di.WorkforcePresentationModule
import dev.fajar.starter.workspace.data.di.WorkspaceDataModule
import dev.fajar.starter.workspace.presentation.di.WorkspacePresentationModule
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.koin.ksp.generated.module

/** Platform entry points own this isolated container and its lifetime. */
fun createAppContainer(
    preferences: UserPreferencesStore,
    database: AppDatabase,
    notificationPlatform: Module,
    environment: AppEnvironment = BuildEnvironment.current,
    workScheduler: WorkScheduler? = null,
    credentials: CredentialStore = MemoryCredentialStore(),
    inboxCredentials: CredentialStore = MemoryCredentialStore(),
    calendarCredentials: CredentialStore = MemoryCredentialStore(),
    leaveCredentials: CredentialStore = MemoryCredentialStore(),
    leaveReplicaCredentials: CredentialStore = MemoryCredentialStore(),
    overtimeReplicaCredentials: CredentialStore = MemoryCredentialStore(),
    workforceReplicaCredentials: CredentialStore = MemoryCredentialStore(),
    payrollReplicaCredentials: CredentialStore = MemoryCredentialStore(),
    expenseReplicaCredentials: CredentialStore = MemoryCredentialStore(),
    directoryReplicaCredentials: CredentialStore = MemoryCredentialStore(),
    selfProfileReplicaCredentials: CredentialStore = MemoryCredentialStore(),
    documentCatalogCredentials: CredentialStore = MemoryCredentialStore(),
    commandCredentials: AtomicCredentialStore = MemoryCredentialStore(),
    fileCredentials: AtomicCredentialStore = MemoryCredentialStore(),
    workspaceCredentials: AtomicCredentialStore = MemoryCredentialStore(),
    pushCredentials: CredentialStore = MemoryCredentialStore(),
    inboxPushCredentials: CredentialStore = MemoryCredentialStore(),
    remoteFeatureFlags: RemoteFeatureFlagSource = UnavailableFeatureFlagSource(),
    deviceAuthentication: DeviceAuthenticationSource = UnavailableDeviceAuthenticationSource(),
    browserAuthorization: BrowserAuthorizationSource = UnavailableBrowserAuthorizationSource(),
    firebaseOptions: FirebaseClientOptionsSource = UnavailableFirebaseClientOptionsSource(),
    googleCredentials: GoogleCredentialSource = UnavailableGoogleCredentialSource(),
    platform: AppPlatform = AppPlatform.Desktop,
    transferInputs: TransferInputSource = UnavailableTransferInputSource(),
    location: LocationDataSource = UnavailableLocationDataSource(),
    fileInputs: FileInputSource = UnavailableFileInputSource(),
    fileOutputs: FileOutputSource = UnavailableFileOutputSource(),
    http: Module? = null,
) = koinApplication {
    modules(applicationFeatureModules())
    modules(
        notificationPlatform,
        http ?: applicationNetworkModule(environment),
        applicationStorageModule(database, preferences),
        applicationWorkerModule(),
        applicationFirebaseModule(firebaseOptions, googleCredentials),
        TransferDataModule().module,
        SecurityPresentationModule().module,
        AvailabilityDataModule().module,
        AvailabilityPresentationModule().module,
        SecurityDataModule().module,
        SettingsDataModule().module,
        SettingsPresentationModule().module,
        SyncDataModule().module,
        FeatureFlagDataModule().module,
        NotificationDataModule().module,
        LocationDataModule().module,
        module {
            single { environment }
            single<LocationDataSource> { location }.onClose { it?.close() }
            single<FileInputSource> { fileInputs }.onClose { it?.close() }
            single<FileOutputSource> { fileOutputs }.onClose { it?.close() }
            single {
                IdentityServer(
                    BuildRuntime.apiEndpoints.getValue(environment),
                    "HRIS Mobile (${platform.key})",
                )
            }

            single<TransferInputSource> { transferInputs }
            single { SsoConfiguration(emptyList(), platform) }
            single<BrowserAuthorizationSource> { browserAuthorization }

            single<DeviceAuthenticationSource> { deviceAuthentication }
            single {
                AppBuild(
                    platform,
                    BuildRuntime.versionNumber,
                    BuildRuntime.versionName,
                    BuildRuntime.updateUrls[platform]?.takeIf { it.isNotBlank() },
                )
            }
            single<RemoteFeatureFlagSource> { remoteFeatureFlags }
            single<WorkScheduler> {
                    workScheduler
                        ?: ForegroundWorkScheduler(getAll<SyncTask>().map { it.key }.toSet())
                }
                .onClose { (it as? ForegroundWorkScheduler)?.close() }
            single<CredentialStore> { credentials }.onClose { it?.close() }
            single<CredentialStore>(named("native-push-state")) { pushCredentials }
                .onClose { it?.close() }
            single<CredentialStore>(named("inbox-push-queue")) { inboxPushCredentials }
                .onClose { it?.close() }
            single<CredentialStore>(named("inbox-content-key")) { inboxCredentials }
                .onClose { it?.close() }
            single<CredentialStore>(named("calendar-content-key")) { calendarCredentials }
                .onClose { it?.close() }
            single<CredentialStore>(named("leave-content-key")) { leaveCredentials }
                .onClose { it?.close() }
            single<CredentialStore>(named("leave-replica-content-key")) { leaveReplicaCredentials }
                .onClose { it?.close() }
            single<CredentialStore>(named("workforce-replica-content-key")) {
                    workforceReplicaCredentials
                }
                .onClose { it?.close() }
            single<CredentialStore>(named("payroll-replica-content-key")) {
                    payrollReplicaCredentials
                }
                .onClose { it?.close() }
            single<CredentialStore>(named("expense-replica-content-key")) {
                    expenseReplicaCredentials
                }
                .onClose { it?.close() }
            single<CredentialStore>(named("directory-replica-content-key")) {
                    directoryReplicaCredentials
                }
                .onClose { it?.close() }
            single<CredentialStore>(named("self-profile-replica-content-key")) {
                    selfProfileReplicaCredentials
                }
                .onClose { it?.close() }
            single<CredentialStore>(named("document-catalog-content-key")) {
                    documentCatalogCredentials
                }
                .onClose { it?.close() }
            single<CredentialStore>(named("overtime-replica-content-key")) {
                    overtimeReplicaCredentials
                }
                .onClose { it?.close() }
            single<AtomicCredentialStore>(named("command-content-key")) { commandCredentials }
                .onClose { it?.close() }
            single<AtomicCredentialStore>(named("file-content-key")) { fileCredentials }
                .onClose { it?.close() }
            single<AtomicCredentialStore>(named("workspace-content-key")) { workspaceCredentials }
                .onClose { it?.close() }
        },
    )
}

/** Feature modules own their registrations; the app only composes their graphs. */
private fun applicationFeatureModules(): List<Module> =
    listOf(
        IdentityModule().module,
        NotificationPresentationModule().module,
        WorkspaceDataModule().module,
        DocumentsDataModule().module,
        OnboardingDataModule().module,
        OnboardingPresentationModule().module,
        AuthPresentationModule().module,
        WorkspacePresentationModule().module,
        HomePresentationModule().module,
        PeopleDataModule().module,
        PeoplePresentationModule().module,
        InboxDataModule().module,
        InboxPresentationModule().module,
        WorkforceDataModule().module,
        WorkforcePresentationModule().module,
        LeaveDataModule().module,
        ExpenseDataModule().module,
        ExpensePresentationModule().module,
        PayrollDataModule().module,
        PayrollPresentationModule().module,
        LeavePresentationModule().module,
        DocumentsPresentationModule().module,
        ApplicationModule().module,
    )
