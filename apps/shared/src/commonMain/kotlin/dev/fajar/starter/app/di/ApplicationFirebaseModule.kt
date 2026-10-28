package dev.fajar.starter.app.di

import dev.fajar.starter.identity.data.firebase.datasources.*
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

fun applicationFirebaseModule(
    options: FirebaseClientOptionsSource,
    google: GoogleCredentialSource,
    engineFactory: () -> HttpClientEngine = ::createPlatformHttpEngine,
) = module {
    single<FirebaseClientOptionsSource> { options }
    single<GoogleCredentialSource> { google }.onClose { it?.close() }
    single<HttpClient>(named(HttpClients.FirebaseAuth)) {
            // The protocol fixes Google's absolute URL. A distinct origin omits internal trace
            // context.
            createHttpClient(
                engineFactory(),
                HttpClientSettings(
                    "https://firebase-auth.invalid/",
                    requestTimeoutMillis = 15_000,
                    connectTimeoutMillis = 5_000,
                    socketTimeoutMillis = 10_000,
                ),
            )
        }
        .onClose { it?.cancelAndClose() }
}
