package dev.fajar.starter

import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationInputRepository
import dev.fajar.starter.collaboration.domain.usecases.RetainCollaborationNotificationReply
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.network.HttpClients
import dev.fajar.starter.network.cancelAndClose
import dev.fajar.starter.securestorage.AtomicCredentialStore
import dev.fajar.starter.securestorage.MemoryCredentialStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

class CollaborationNotificationInputBindingsTest {
    @Test
    fun containerOwnsDeviceKeyAndReopensProtectedInputWithoutCreatingAccountAuthority() =
        runBlocking {
            val directory = Files.createTempDirectory("notification-input-bindings").toFile()
            val protectedRecord = MemoryCredentialStore()
            val closes = AtomicInteger()
            val requests = AtomicInteger()
            fun open() =
                createAppContainer(
                    createUserPreferences(directory),
                    createAppDatabase(directory),
                    module {},
                    collaborationNotificationInputCredentials =
                        object : AtomicCredentialStore by protectedRecord {
                            private var closed = false

                            override suspend fun read(): String? {
                                check(!closed)
                                return protectedRecord.read()
                            }

                            override fun close() {
                                check(!closed)
                                closed = true
                                closes.incrementAndGet()
                            }
                        },
                    http =
                        module {
                            for (qualifier in
                                listOf(HttpClients.Public, HttpClients.Authenticated)) {
                                single<HttpClient>(named(qualifier)) {
                                        HttpClient(
                                            MockEngine {
                                                requests.incrementAndGet()
                                                error("No network before account admission")
                                            }
                                        )
                                    }
                                    .onClose { it?.cancelAndClose() }
                            }
                        },
                )
            var container = open()
            try {
                val repository = container.koin.get<CollaborationNotificationInputRepository>()
                assertSame(
                    repository,
                    container.koin.get<CollaborationNotificationInputRepository>(),
                )
                val reply =
                    CollaborationUnverifiedNotificationReply(
                        CollaborationNotificationActionToken("v2.ZmFrZQ=="),
                        "Retained before sign-in",
                    )
                val input =
                    assertIs<CollaborationNotificationInputRetention.Retained>(
                            assertIs<AppResult.Success<CollaborationNotificationInputRetention>>(
                                    repository.retain(reply)
                                )
                                .value
                        )
                        .input
                assertIs<AppResult.Failed>(
                    container.koin.get<RetainCollaborationNotificationReply>()(
                        reply.token,
                        reply.text,
                    )
                )
                assertEquals(
                    input,
                    assertIs<AppResult.Success<CollaborationNotificationInput?>>(
                            repository.get(input.reference)
                        )
                        .value,
                )
                container.close()
                assertEquals(1, closes.get())
                container = open()
                val reopened = container.koin.get<CollaborationNotificationInputRepository>()
                assertEquals(
                    input,
                    assertIs<AppResult.Success<CollaborationNotificationInput?>>(
                            reopened.get(input.reference)
                        )
                        .value,
                )
                assertIs<AppResult.Failed>(
                    container.koin.get<RetainCollaborationNotificationReply>()(
                        reply.token,
                        reply.text,
                    )
                )
                assertEquals(
                    input,
                    assertIs<AppResult.Success<CollaborationNotificationInput?>>(
                            reopened.get(input.reference)
                        )
                        .value,
                )
                assertEquals(0, requests.get())
            } finally {
                container.close()
                directory.deleteRecursively()
            }
            assertEquals(2, closes.get())
        }
}
