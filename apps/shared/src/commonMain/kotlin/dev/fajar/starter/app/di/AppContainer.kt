package dev.fajar.starter.app.di

import dev.fajar.starter.auth.presentation.di.AuthPresentationModule
import dev.fajar.starter.availability.data.di.AvailabilityDataModule
import dev.fajar.starter.availability.presentation.di.AvailabilityPresentationModule
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.config.BuildEnvironment
import dev.fajar.starter.common.config.BuildOidc
import dev.fajar.starter.common.config.BuildRuntime
import dev.fajar.starter.dashboard.data.di.DashboardDataModule
import dev.fajar.starter.dashboard.presentation.di.DashboardPresentationModule
import dev.fajar.starter.database.AccountCacheStore
import dev.fajar.starter.database.AppDatabase
import dev.fajar.starter.database.DashboardStore
import dev.fajar.starter.database.InboxStore
import dev.fajar.starter.database.TransferStore
import dev.fajar.starter.datastore.UserPreferencesStore
import dev.fajar.starter.demo.DemoBrowserAuthorizationSource
import dev.fajar.starter.demo.SampleTransferInputSource
import dev.fajar.starter.featureflags.data.datasources.RemoteFeatureFlagSource
import dev.fajar.starter.featureflags.data.datasources.UnavailableFeatureFlagSource
import dev.fajar.starter.featureflags.data.di.FeatureFlagDataModule
import dev.fajar.starter.files.presentation.di.FilesPresentationModule
import dev.fajar.starter.identity.data.di.IdentityModule
import dev.fajar.starter.identity.data.sso.config.SsoConfiguration
import dev.fajar.starter.identity.data.sso.datasources.BrowserAuthorizationSource
import dev.fajar.starter.identity.data.sso.datasources.UnavailableBrowserAuthorizationSource
import dev.fajar.starter.notifications.data.di.NotificationDataModule
import dev.fajar.starter.notifications.presentation.di.NotificationPresentationModule
import dev.fajar.starter.onboarding.data.di.OnboardingDataModule
import dev.fajar.starter.onboarding.presentation.di.OnboardingPresentationModule
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
import dev.fajar.starter.transfers.data.di.TransferDataModule
import dev.fajar.starter.worker.ForegroundWorkScheduler
import org.koin.core.module.Module
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
    remoteFeatureFlags: RemoteFeatureFlagSource = UnavailableFeatureFlagSource(),
    deviceAuthentication: DeviceAuthenticationSource = UnavailableDeviceAuthenticationSource(),
    browserAuthorization: BrowserAuthorizationSource = UnavailableBrowserAuthorizationSource(),
    platform: AppPlatform = AppPlatform.Desktop,
    transferInputs: TransferInputSource = SampleTransferInputSource(),
) = koinApplication {
    modules(
        notificationPlatform,
        applicationNetworkModule(environment),
        TransferDataModule().module,
        FilesPresentationModule().module,
        SecurityPresentationModule().module,
        AvailabilityDataModule().module,
        AvailabilityPresentationModule().module,
        SecurityDataModule().module,
        SettingsDataModule().module,
        SettingsPresentationModule().module,
        SyncDataModule().module,
        FeatureFlagDataModule().module,
        NotificationDataModule().module,
        NotificationPresentationModule().module,
        module {
            single { environment }

            single<TransferInputSource> { transferInputs }
            single<TransferStore> { get<AppDatabase>().transfers }
            single {
                SsoConfiguration(
                    if (BuildRuntime.demoBackend) dev.fajar.starter.demo.demoOidcClients()
                    else BuildOidc.clients[environment].orEmpty(),
                    platform,
                )
            }
            single<BrowserAuthorizationSource> {
                if (BuildRuntime.demoBackend) DemoBrowserAuthorizationSource()
                else browserAuthorization
            }

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
            single<AccountCacheStore> { get<AppDatabase>().accounts }
            single<InboxStore> { get<AppDatabase>().inbox }
            single<DashboardStore> { get<AppDatabase>().dashboard }
            single<UserPreferencesStore> { preferences }.onClose { it?.close() }
        },
        IdentityModule().module,
        OnboardingDataModule().module,
        OnboardingPresentationModule().module,
        AuthPresentationModule().module,
        DashboardDataModule().module,
        DashboardPresentationModule().module,
        ApplicationModule().module,
    )
}
