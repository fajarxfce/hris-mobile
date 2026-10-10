package dev.fajar.starter.app.di

import dev.fajar.starter.availability.domain.usecases.CheckAppAvailability
import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.config.BuildRuntime
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.demo.DemoTransferServer
import dev.fajar.starter.demo.createDemoEngine
import dev.fajar.starter.identity.domain.usecases.AcquireSessionTokens
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.http.Url
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

/** The application container owns each client and closes it with its platform lifetime. */
fun applicationNetworkModule(environment: AppEnvironment) = module {
    single { DemoTransferServer() }
    single<HttpClient>(named(HttpClients.Oidc)) {
            createHttpClient(
                if (BuildRuntime.demoBackend) createDemoEngine(transfers = get())
                else createPlatformHttpEngine(),
                HttpClientSettings("https://oidc.invalid/"),
            )
        }
        .onClose { it?.close() }
    single<HttpClient>(named(HttpClients.Public)) {
            val policy = get<CheckAppAvailability>()
            createHttpClient(
                if (BuildRuntime.demoBackend) createDemoEngine(transfers = get())
                else createPlatformHttpEngine(),
                HttpClientSettings(BuildRuntime.apiEndpoints.getValue(environment)),
            ) {
                install(ApplicationAvailability) { check = { policy() } }
            }
        }
        .onClose { it?.close() }
    single<HttpClient>(named(HttpClients.Authenticated)) {
            val acquireTokens = get<AcquireSessionTokens>()
            val policy = get<CheckAppAvailability>()
            val endpoint = BuildRuntime.apiEndpoints.getValue(environment)
            createHttpClient(
                if (BuildRuntime.demoBackend) createDemoEngine(transfers = get())
                else createPlatformHttpEngine(),
                HttpClientSettings(endpoint),
            ) {
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
        .onClose { it?.close() }
}
