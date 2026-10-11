package dev.fajar.starter.app.di

import dev.fajar.starter.auth.presentation.di.AuthPresentationModule
import dev.fajar.starter.availability.data.di.AvailabilityDataModule
import dev.fajar.starter.availability.presentation.di.AvailabilityPresentationModule
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.config.BuildEnvironment
import dev.fajar.starter.common.config.BuildRuntime
import dev.fajar.starter.database.AccountCacheStore
import dev.fajar.starter.database.AppDatabase
import dev.fajar.starter.database.CommandStore
import dev.fajar.starter.database.InboxStore
import dev.fajar.starter.database.ReplicaStore
import dev.fajar.starter.database.TransferStore
import dev.fajar.starter.datastore.UserPreferencesStore
import dev.fajar.starter.featureflags.data.datasources.RemoteFeatureFlagSource
import dev.fajar.starter.featureflags.data.datasources.UnavailableFeatureFlagSource
import dev.fajar.starter.featureflags.data.di.FeatureFlagDataModule
import dev.fajar.starter.home.presentation.di.HomePresentationModule
import dev.fajar.starter.identity.data.di.IdentityModule
import dev.fajar.starter.identity.data.sso.config.SsoConfiguration
import dev.fajar.starter.identity.data.sso.datasources.BrowserAuthorizationSource
import dev.fajar.starter.identity.data.sso.datasources.UnavailableBrowserAuthorizationSource
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.inbox.data.di.InboxDataModule
import dev.fajar.starter.inbox.presentation.di.InboxPresentationModule
import dev.fajar.starter.notifications.data.di.NotificationDataModule
import dev.fajar.starter.onboarding.data.di.OnboardingDataModule
import dev.fajar.starter.onboarding.presentation.di.OnboardingPresentationModule
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
    commandCredentials: AtomicCredentialStore = MemoryCredentialStore(),
    workspaceCredentials: AtomicCredentialStore = MemoryCredentialStore(),
    remoteFeatureFlags: RemoteFeatureFlagSource = UnavailableFeatureFlagSource(),
    deviceAuthentication: DeviceAuthenticationSource = UnavailableDeviceAuthenticationSource(),
    browserAuthorization: BrowserAuthorizationSource = UnavailableBrowserAuthorizationSource(),
    platform: AppPlatform = AppPlatform.Desktop,
    transferInputs: TransferInputSource = UnavailableTransferInputSource(),
    http: Module? = null,
) = koinApplication {
    modules(
        notificationPlatform,
        http ?: applicationNetworkModule(environment),
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
        module {
            single { environment }
            single {
                IdentityServer(
                    BuildRuntime.apiEndpoints.getValue(environment),
                    "HRIS Mobile (${platform.key})",
                )
            }

            single<TransferInputSource> { transferInputs }
            single<TransferStore> { get<AppDatabase>().transfers }
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
            single<AppDatabase>(createdAtStart = true) { database }.onClose { it?.close() }
            single<CredentialStore> { credentials }.onClose { it?.close() }
            single<CredentialStore>(named("inbox-content-key")) { inboxCredentials }
                .onClose { it?.close() }
            single<AtomicCredentialStore>(named("command-content-key")) { commandCredentials }
                .onClose { it?.close() }
            single<AtomicCredentialStore>(named("workspace-content-key")) { workspaceCredentials }
                .onClose { it?.close() }
            single<CommandStore> { get<AppDatabase>().commands }
            single<ContentCipher> { AesGcmContentCipher() }
            single<ReplicaStore> { get<AppDatabase>().replicas }
            single<AccountCacheStore> { get<AppDatabase>().accounts }
            single<InboxStore> { get<AppDatabase>().inbox }
            single<UserPreferencesStore> { preferences }.onClose { it?.close() }
        },
        IdentityModule().module,
        WorkspaceDataModule().module,
        OnboardingDataModule().module,
        OnboardingPresentationModule().module,
        AuthPresentationModule().module,
        WorkspacePresentationModule().module,
        HomePresentationModule().module,
        PeopleDataModule().module,
        PeoplePresentationModule().module,
        InboxDataModule().module,
        InboxPresentationModule().module,
        ApplicationModule().module,
    )
}
