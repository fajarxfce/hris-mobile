package dev.fajar.starter.app.di

import dev.fajar.starter.auth.presentation.di.AuthPresentationModule
import dev.fajar.starter.dashboard.data.di.DashboardDataModule
import dev.fajar.starter.dashboard.presentation.di.DashboardPresentationModule
import dev.fajar.starter.demo.createDemoEngine
import dev.fajar.starter.identity.data.di.IdentityModule
import dev.fajar.starter.network.createHttpClient
import dev.fajar.starter.onboarding.data.di.OnboardingDataModule
import dev.fajar.starter.onboarding.presentation.di.OnboardingPresentationModule
import dev.fajar.starter.storage.PreferenceStore
import io.ktor.client.HttpClient
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.koin.ksp.generated.module

/** Platform entry points own this isolated container and its lifetime. */
fun createAppContainer(preferences: PreferenceStore) = koinApplication {
    modules(
        module {
            single<PreferenceStore> { preferences }
            single<HttpClient> {
                    createHttpClient(createDemoEngine(), "https://demo.fluent.local/")
                }
                .onClose { it?.close() }
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
