package dev.fajar.starter.app.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.config.BuildRuntime
import dev.fajar.starter.identity.domain.entities.IdentityServer
import org.koin.dsl.module

/** Platform selection determines the build identity and server configuration for this container. */
fun applicationEnvironmentModule(environment: AppEnvironment, platform: AppPlatform) = module {
    single { environment }
    single {
        IdentityServer(
            BuildRuntime.apiEndpoints.getValue(environment),
            "HRIS Mobile (${platform.key})",
        )
    }
    single {
        AppBuild(
            platform,
            BuildRuntime.versionNumber,
            BuildRuntime.versionName,
            BuildRuntime.updateUrls[platform]?.takeIf { it.isNotBlank() },
        )
    }
}
