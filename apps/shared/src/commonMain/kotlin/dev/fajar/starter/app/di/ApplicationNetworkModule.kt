package dev.fajar.starter.app.di

import dev.fajar.starter.availability.domain.usecases.CheckAppAvailability
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.config.BuildRuntime
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.usecases.AcquireSessionTokens
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.Url
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

/** The application container owns each client and closes it with its platform lifetime. */
fun applicationNetworkModule(
    environment: AppEnvironment,
    engineFactory: () -> HttpClientEngine = ::createPlatformHttpEngine,
) = module {
    single<HttpClient>(named(HttpClients.Oidc)) {
            createHttpClient(engineFactory(), HttpClientSettings("https://oidc.invalid/"))
        }
        .onClose { it?.cancelAndClose() }
    single<HttpClient>(named(HttpClients.Public)) {
            val policy = get<CheckAppAvailability>()
            val build = get<AppBuild>()
            createHttpClient(
                engineFactory(),
                HttpClientSettings(
                    BuildRuntime.apiEndpoints.getValue(environment),
                    allowCleartext = environment == AppEnvironment.Dev,
                ),
            ) {
                defaultRequest {
                    if (build.platform in setOf(AppPlatform.Android, AppPlatform.Ios)) {
                        header("X-HRIS-Client-Platform", build.platform.key.uppercase())
                        header("X-HRIS-Client-Build", build.number)
                    }
                }
                install(ApplicationAvailability) { check = { policy() } }
            }
        }
        .onClose { it?.cancelAndClose() }
    single<HttpClient>(named(HttpClients.Authenticated)) {
            val acquireTokens = get<AcquireSessionTokens>()
            val policy = get<CheckAppAvailability>()
            val endpoint = BuildRuntime.apiEndpoints.getValue(environment)
            val build = get<AppBuild>()
            createHttpClient(
                engineFactory(),
                HttpClientSettings(endpoint, allowCleartext = environment == AppEnvironment.Dev),
            ) {
                defaultRequest {
                    if (build.platform in setOf(AppPlatform.Android, AppPlatform.Ios)) {
                        header("X-HRIS-Client-Platform", build.platform.key.uppercase())
                        header("X-HRIS-Client-Build", build.number)
                    }
                }
                install(ApplicationAvailability) { check = { policy() } }
                install(SessionAuthentication) {
                    origin = Url(endpoint)
                    acquire = { sessionId, rejected ->
                        when (val result = acquireTokens(sessionId, rejected)) {
                            is AppResult.Failed -> result
                            is AppResult.Success -> AppResult.Success(result.value?.accessToken)
                        }
                    }
                }
            }
        }
        .onClose { it?.cancelAndClose() }
}
